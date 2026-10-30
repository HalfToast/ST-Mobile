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
 * One-off password login that appends the app's public key line to the server's authorized_keys.
 * The password is used for this single connection only and is never stored or logged.
 */
object KeyInstaller {
    /** The script goes on stdin, so any login shell (bash, zsh, fish) runs it the same way. */
    const val INSTALL_COMMAND: String = "env bash -s"

    private val KEY_LINE = Regex("""ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+ st-mobile""")

    private const val TIMEOUT_MS = 30_000L

    /**
     * The script that appends [line] to authorized_keys unless it is already there. Throws
     * [IllegalArgumentException] unless [line] is the app's own key line (KeyManager), which is
     * what makes embedding it in single quotes safe.
     */
    fun installScript(line: String): String {
        require(KEY_LINE.matches(line)) { "not the app's key line" }
        return "KEY='$line'\n" +
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "{ grep -qxF \"\$KEY\" ~/.ssh/authorized_keys || printf '%s\\n' \"\$KEY\" >> ~/.ssh/authorized_keys; }\n"
    }

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
            val script = installScript(line) // before anything connects
            withSshTimeout(TIMEOUT_MS) {
                runInterruptible { installBlocking(host, port, user, password, script, knownHostKey) }
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
        script: String,
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
                throw SshError.Unreachable("Can't reach the server", e)
            }
            client.startSession().use { session ->
                val cmd = session.exec(INSTALL_COMMAND)
                cmd.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
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
