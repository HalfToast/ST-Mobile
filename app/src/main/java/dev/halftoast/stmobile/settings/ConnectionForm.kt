package dev.halftoast.stmobile.settings

import java.net.URI

/** Editable connection fields shared by Setup and Settings (everything but the password). */
data class ConnectionForm(
    val serverUrl: String,
    val host: String,
    val port: String,
    val user: String,
    val stUserDir: String,
    val stDir: String,
) {
    /** SSH is wanted once a host or user is entered; the URL alone is enough otherwise. */
    val hasSsh: Boolean get() = host.isNotBlank() || user.isNotBlank()

    /** First problem with the form, or null if it can be saved. */
    fun validate(): String? = when {
        !AppSettings.isValidServerUrl(serverUrl) -> "Server URL must start with http:// or https://"
        hasSsh && (host.isBlank() || host.any { it.isWhitespace() }) -> "Enter the server's SSH host"
        hasSsh && port.toIntOrNull()?.let { it in 1..65535 } != true -> "Port must be a number from 1 to 65535"
        hasSsh && (user.isBlank() || user.any { it.isWhitespace() }) -> "Enter the SSH user"
        !isValidStUserDir(stUserDir.trim()) -> ST_USER_DIR_ERROR
        !isValidStDir(stDir.trim()) -> ST_DIR_ERROR
        else -> null
    }

    /** A blank SSH host means the server URL's host, once an SSH user is given. */
    fun withDefaultHost(): ConnectionForm =
        if (host.isBlank() && user.isNotBlank()) copy(host = defaultSshHost(serverUrl)) else this

    /**
     * Saves the SSH fields and folders; a different host or port unpins the old host key (pins are
     * per host). Without SSH, host and user are saved empty and an empty or out-of-range
     * port as [AppSettings.DEFAULT_SSH_PORT].
     */
    fun saveSsh(settings: AppSettings) {
        val newHost = host.trim()
        val newPort = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: AppSettings.DEFAULT_SSH_PORT
        if (newHost != settings.sshHost || newPort != settings.sshPort) settings.knownHostKey = null
        settings.sshHost = newHost
        settings.sshPort = newPort
        settings.sshUser = user.trim()
        settings.stUserDir = stUserDir.trim()
        settings.stDir = stDir.trim()
    }

    /** Saves the server URL; returns true if it changed. */
    fun saveServerUrl(settings: AppSettings): Boolean {
        val url = serverUrl.trim()
        if (url == settings.serverUrl) return false
        settings.serverUrl = url
        return true
    }

    companion object {
        const val ST_USER_DIR_ERROR = "ST user folder may only use letters, digits, '.', '_' and '-', like default-user"
        const val ST_DIR_ERROR =
            "SillyTavern folder must start with / or ~/ and may only use letters, digits, spaces, '.', '_', '/', '+' and '-'"
        private val ST_USER_DIR = Regex("[A-Za-z0-9._-]+")
        private val ST_DIR = Regex("""(/|~/)[A-Za-z0-9 ._/+-]*""")

        /**
         * The folder name reaches shell commands on the server, so only [A-Za-z0-9._-]+ is allowed,
         * and additionally not dots only ("." / "..") and no leading '-' (would read as an option).
         */
        fun isValidStUserDir(name: String): Boolean =
            ST_USER_DIR.matches(name) && name.any { it != '.' } && !name.startsWith("-")

        /**
         * The SillyTavern folder goes into a single-quoted `ST_DIR='…'` on the SSH command line:
         * empty (find it automatically), or an absolute or `~/` path of [A-Za-z0-9 ._/+-].
         */
        fun isValidStDir(path: String): Boolean = path.isEmpty() || ST_DIR.matches(path)

        fun from(settings: AppSettings) = ConnectionForm(
            serverUrl = settings.serverUrl,
            host = settings.sshHost,
            port = settings.sshPort.toString(),
            user = settings.sshUser,
            stUserDir = settings.stUserDir,
            stDir = settings.stDir,
        )
    }
}

/** The host of [serverUrl] (IPv6 without brackets), the usual SSH host; `""` if it isn't a valid URL. */
fun defaultSshHost(serverUrl: String): String {
    if (!AppSettings.isValidServerUrl(serverUrl)) return ""
    return URI(serverUrl.trim()).host.orEmpty().removePrefix("[").removeSuffix("]")
}
