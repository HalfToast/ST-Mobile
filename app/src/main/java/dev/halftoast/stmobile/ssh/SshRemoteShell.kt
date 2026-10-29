package dev.halftoast.stmobile.ssh

import dev.halftoast.stmobile.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.IOException
import java.io.InputStream
import java.security.PublicKey

/**
 * Key-authenticated shell on the tablet. One connection is reused across calls and replaced when
 * it drops, when the host/port/user settings change, or when opening a session on it fails.
 * Host keys are pinned (TOFU) in [AppSettings.knownHostKey] via [HostKeyPolicy].
 */
class SshRemoteShell(private val settings: AppSettings, private val keys: KeyManager) : RemoteShell {
    private data class Target(val host: String, val port: Int, val user: String)
    private class Live(val target: Target, val client: SSHClient)

    private val mutex = Mutex()
    @Volatile private var live: Live? = null

    override suspend fun exec(command: String, stdin: String?, timeoutMs: Long): ExecResult =
        withContext(Dispatchers.IO) {
            withSshTimeout(timeoutMs) {
                val session = openSession()
                try {
                    runCommand(session, command, stdin)
                } catch (e: CancellationException) {
                    session.terminate() // timed out or cancelled: don't leave the command running
                    throw e
                } catch (e: IOException) {
                    // Timed out or cancelled: interrupted reads surface as InterruptedIOException.
                    // Keep the shared connection; withSshTimeout reports the timeout.
                    if (!currentCoroutineContext().isActive) session.terminate()
                    currentCoroutineContext().ensureActive()
                    // Otherwise the connection died under us; the next call reconnects.
                    discard()
                    throw SshError.Unreachable("Lost the connection to the tablet", e)
                } finally {
                    session.closeQuietly()
                }
            }
        }

    /**
     * Emits stdout lines of [command] (append `2>&1` to include stderr). When the collector stops
     * early, TERM is sent to the remote command (also if cancellation lands while exec is still
     * waiting for its reply) and the session is closed. Termux sshd keeps a non-PTY command running
     * after a plain channel close, so the signal is what stops e.g. `tail -f`.
     */
    override fun stream(command: String): Flow<String> = channelFlow {
        val session = openSession()
        try {
            val c = runInterruptible { session.exec(command) }
            c.outputStream.close()
            launch { runCatching { runInterruptible { drain(c.errorStream) } } } // keep the window open
            val reader = c.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = runInterruptible { reader.readLine() } ?: break
                send(line)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            discard()
            throw SshError.Unreachable("Lost the connection to the tablet", e)
        } finally {
            session.terminate()
            session.closeQuietly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Drops the cached connection. Suspends and runs on IO (sshj's disconnect writes to the socket;
     * on Main it throws NetworkOnMainThreadException and leaks the socket and the sshd session).
     * Not cancellable once started, so the disconnect always finishes.
     */
    suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val old = live
            live = null
            old?.client?.closeQuietly()
        }
    }

    /**
     * The fingerprint the tablet presents right now, without authenticating (nothing is sent but
     * the key exchange). For showing the user what "Trust new host key" would pin.
     */
    suspend fun presentedHostKey(): String = withContext(Dispatchers.IO) {
        withSshTimeout(15_000) {
            runInterruptible { SshConnect.fetchHostKey(settings.sshHost, settings.sshPort) }
        }
    }

    private suspend fun runCommand(session: Session, command: String, stdin: String?): ExecResult = coroutineScope {
        val cmd = runInterruptible { session.exec(command) }
        runInterruptible {
            cmd.outputStream.use { out -> stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)); out.flush() } }
        }
        val err = async { runInterruptible { cmd.errorStream.readBytes() } }
        val out = runInterruptible { cmd.inputStream.readBytes() }
        val errBytes = err.await()
        runInterruptible { cmd.join() }
        ExecResult(cmd.exitStatus, out.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /** Opens a session, reconnecting once if the cached connection turns out to be dead. */
    private suspend fun openSession(): Session {
        val first = client()
        return try {
            runInterruptible { first.startSession() }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            discard(first)
            val second = client()
            try {
                runInterruptible { second.startSession() }
            } catch (e2: IOException) {
                currentCoroutineContext().ensureActive()
                discard(second)
                throw SshError.Unreachable("Can't reach the tablet", e2)
            }
        }
    }

    private suspend fun client(): SSHClient = mutex.withLock {
        val target = Target(settings.sshHost, settings.sshPort, settings.sshUser)
        live?.let {
            if (it.target == target && it.client.isConnected && it.client.isAuthenticated) return it.client
            it.client.closeQuietly()
            live = null
        }
        val client = runInterruptible { connect(target) }
        live = Live(target, client)
        client
    }

    private fun connect(target: Target): SSHClient {
        val keyPair = keys.getOrCreate()
        val known = settings.knownHostKey
        val (client, verifier) = SshConnect.open(target.host, target.port, known)
        try {
            client.authPublickey(target.user, KeyPairWrapper(keyPair))
        } catch (e: UserAuthException) {
            client.closeQuietly()
            throw SshError.AuthFailed("The tablet rejected the app's key", e)
        } catch (e: IOException) {
            client.closeQuietly()
            throw SshError.Unreachable("Can't reach the tablet", e)
        }
        if (known.isNullOrEmpty()) verifier.seen?.let { settings.knownHostKey = it } // TrustAndStore
        return client
    }

    private suspend fun discard(client: SSHClient? = null) = mutex.withLock {
        val current = live
        if (current != null && (client == null || current.client === client)) {
            live = null
            current.client.closeQuietly()
        } else {
            client?.closeQuietly()
        }
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(4096)
        while (true) {
            if (input.read(buf) < 0) return
        }
    }
}

/** Applies [HostKeyPolicy] during key exchange and remembers what it saw. */
internal class PolicyHostKeyVerifier(private val known: String?) : HostKeyVerifier {
    @Volatile var seen: String? = null
        private set
    @Volatile var rejected: Boolean = false
        private set

    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
        val fp = HostKeyPolicy.fingerprint(key)
        seen = fp
        return when (HostKeyPolicy.decide(known, fp)) {
            HostKeyDecision.TrustAndStore, HostKeyDecision.Accept -> true
            HostKeyDecision.Reject -> {
                rejected = true
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
}

internal object SshConnect {
    const val CONNECT_TIMEOUT_MS = 5_000

    /** Key exchange only: records the host key, refuses it so no auth happens, disconnects. Blocking. */
    fun fetchHostKey(host: String, port: Int): String {
        var seen: String? = null
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                seen = HostKeyPolicy.fingerprint(key)
                return false
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        var failure: Exception? = null
        try {
            client.connect(host, port)
        } catch (e: Exception) {
            failure = e
        } finally {
            client.closeQuietly()
        }
        return seen ?: throw SshError.Unreachable("Can't reach the tablet", failure)
    }

    /** Connects and verifies the host key (blocking). Throws [SshError.HostKeyChanged] or [SshError.Unreachable]. */
    fun open(host: String, port: Int, knownHostKey: String?): Pair<SSHClient, PolicyHostKeyVerifier> {
        val verifier = PolicyHostKeyVerifier(knownHostKey)
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val client = SSHClient(config)
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.addHostKeyVerifier(verifier)
        // Must be set before connect: sshj starts the keep-alive thread in onConnect if enabled.
        client.connection.keepAlive.keepAliveInterval = 15
        // Fail fast on a dead link instead of waiting the default 30 s for channel opens.
        client.connection.timeoutMs = 10_000
        try {
            client.connect(host, port)
        } catch (e: Exception) {
            client.closeQuietly()
            if (verifier.rejected) throw SshError.HostKeyChanged(knownHostKey.orEmpty(), verifier.seen.orEmpty())
            throw SshError.Unreachable("Can't reach the tablet", e)
        }
        return client to verifier
    }
}

/**
 * [withTimeout] that reports [SshError.Timeout]. An interrupted blocking sshj call can surface as
 * an IOException rather than the timeout itself, so any failure past the deadline counts as a timeout.
 */
internal suspend fun <T> withSshTimeout(timeoutMs: Long, block: suspend CoroutineScope.() -> T): T {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    try {
        return withTimeout(timeoutMs, block)
    } catch (e: TimeoutCancellationException) {
        throw SshError.Timeout("No answer from the tablet within ${timeoutMs / 1000} s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        val late = System.nanoTime() >= deadline
        if (late && e !is SshError.AuthFailed && e !is SshError.HostKeyChanged) {
            throw SshError.Timeout("No answer from the tablet within ${timeoutMs / 1000} s")
        }
        throw e
    }
}

internal fun SSHClient.closeQuietly() {
    runCatching { disconnect() }
}

internal fun Session.closeQuietly() {
    runCatching { close() }
}

/**
 * Sends TERM to the session's command if the channel is open and no exit status arrived yet.
 * Works before exec's reply too: sshd handles channel requests in order, after starting the command.
 */
internal fun Session.terminate() {
    val cmd = this as? Session.Command ?: return
    if (cmd.isOpen && cmd.exitStatus == null) runCatching { cmd.signal(Signal.TERM) }
}
