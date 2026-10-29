package dev.halftoast.stmobile.settings

/** Editable connection fields shared by Setup and Settings (everything but the password). */
data class ConnectionForm(
    val serverUrl: String,
    val host: String,
    val port: String,
    val user: String,
    val stUserDir: String,
) {
    /** First problem with the form, or null if it can be saved. */
    fun validate(): String? = when {
        !AppSettings.isValidServerUrl(serverUrl) -> "Server URL must start with http:// or https://"
        host.isBlank() || host.any { it.isWhitespace() } -> "Enter the tablet's SSH host"
        port.toIntOrNull()?.let { it in 1..65535 } != true -> "Port must be a number from 1 to 65535"
        user.isBlank() || user.any { it.isWhitespace() } -> "Enter the SSH user"
        !isValidStUserDir(stUserDir.trim()) -> ST_USER_DIR_ERROR
        else -> null
    }

    /** Saves the SSH fields; a different host or port unpins the old host key (pins are per host). */
    fun saveSsh(settings: AppSettings) {
        val newHost = host.trim()
        val newPort = port.trim().toInt()
        if (newHost != settings.sshHost || newPort != settings.sshPort) settings.knownHostKey = null
        settings.sshHost = newHost
        settings.sshPort = newPort
        settings.sshUser = user.trim()
        settings.stUserDir = stUserDir.trim()
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
        private val ST_USER_DIR = Regex("[A-Za-z0-9._-]+")

        /**
         * The folder name reaches shell commands on the tablet, so only [A-Za-z0-9._-]+ is allowed,
         * and additionally not dots only ("." / "..") and no leading '-' (would read as an option).
         */
        fun isValidStUserDir(name: String): Boolean =
            ST_USER_DIR.matches(name) && name.any { it != '.' } && !name.startsWith("-")

        /** Suggested user for a blank field: Termux's user on HalfToast's tablet. Editable. */
        const val SUGGESTED_SSH_USER = "u0_a123"

        fun from(settings: AppSettings) = ConnectionForm(
            serverUrl = settings.serverUrl,
            host = settings.sshHost,
            port = settings.sshPort.toString(),
            user = settings.sshUser.ifBlank { SUGGESTED_SSH_USER },
            stUserDir = settings.stUserDir,
        )
    }
}
