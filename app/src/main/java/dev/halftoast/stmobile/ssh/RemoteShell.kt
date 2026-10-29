package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.flow.Flow

interface RemoteShell {
    suspend fun exec(command: String, stdin: String? = null, timeoutMs: Long = 60_000): ExecResult
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
