package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.flow.Flow

interface RemoteShell {
    /**
     * Runs [command] and returns its output. On timeout or cancellation the session is closed; with
     * [signalOnCancel] TERM is sent to the remote command first. Pass false for commands that must
     * finish on the tablet regardless (stpanel actions): the channel just closes and they keep going.
     */
    suspend fun exec(
        command: String,
        stdin: String? = null,
        timeoutMs: Long = 60_000,
        signalOnCancel: Boolean = true,
    ): ExecResult
    fun stream(command: String): Flow<String>
}

data class ExecResult(val exitCode: Int?, val stdout: String, val stderr: String)

sealed class SshError(msg: String) : Exception(msg) {
    class Unreachable(msg: String = "Can't reach the tablet", cause: Throwable? = null) : SshError(msg) {
        init { cause?.let { initCause(it) } }
    }

    class AuthFailed(msg: String = "Authentication failed", cause: Throwable? = null) : SshError(msg) {
        init { cause?.let { initCause(it) } }
    }

    class Timeout(msg: String = "Timed out") : SshError(msg)

    class HostKeyChanged(val expected: String, val actual: String) :
        SshError("Host key changed: pinned $expected, tablet presented $actual")
}
