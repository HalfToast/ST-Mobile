package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One-off password login that appends the app's public key line to the tablet's authorized_keys.
 * The password is used for this single connection only and is never stored or logged.
 */
object KeyInstaller {
    /** Reads the key line from stdin, so the key never appears in the command line. Idempotent. */
    const val INSTALL_SCRIPT: String =
        "read -r KEY; mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "chmod 600 ~/.ssh/authorized_keys && (grep -qxF \"\$KEY\" ~/.ssh/authorized_keys || " +
            "printf '%s\\n' \"\$KEY\" >> ~/.ssh/authorized_keys)"

    val INSTALL_COMMAND: String = "bash -c " + shellQuote(INSTALL_SCRIPT)

    private const val TIMEOUT_MS = 30_000L

    /** POSIX single-quote quoting. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Installs [line] for [user] and returns the SHA256 host key fingerprint seen. [password] is
     * zeroed before this returns, whatever the outcome. If [knownHostKey] is set, a different host
     * key fails with [SshError.HostKeyChanged] before the password is sent.
     */
    suspend fun install(
        host: String,
        port: Int,
        user: String,
        password: CharArray,
        line: String,
        knownHostKey: String? = null,
    ): String = withContext(Dispatchers.IO) {
        try {
            require('\n' !in line && '\r' !in line) { "key line must be a single line" }
            withSshTimeout(TIMEOUT_MS) {
                runInterruptible { installBlocking(host, port, user, password, line, knownHostKey) }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun installBlocking(
        host: String,
        port: Int,
        user: String,
        password: CharArray,
        line: String,
        knownHostKey: String?,
    ): String {
        val (client, verifier) = SshConnect.open(host, port, knownHostKey)
        try {
            // sshj blanks each copy it is handed after writing it to the auth packet.
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            try {
                client.auth(user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            } catch (e: UserAuthException) {
                throw SshError.AuthFailed("Password rejected", e)
            } catch (e: IOException) {
                throw SshError.Unreachable("Can't reach the tablet", e)
            }
            client.startSession().use { session ->
                val cmd = session.exec(INSTALL_COMMAND)
                cmd.outputStream.use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
                val stdout = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
                cmd.join(10, TimeUnit.SECONDS)
                val status = cmd.exitStatus
                if (status != 0) {
                    throw IOException("Key install failed (exit $status): ${(stderr.ifBlank { stdout }).trim().take(300)}")
                }
            }
            return verifier.seen ?: error("no host key seen")
        } finally {
            client.closeQuietly()
        }
    }
}
