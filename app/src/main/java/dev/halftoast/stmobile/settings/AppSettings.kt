package dev.halftoast.stmobile.settings

import android.content.SharedPreferences
import java.net.URI

class AppSettings(private val prefs: SharedPreferences) {

    /** The SillyTavern address; `""` until one is entered. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    val hasServerUrl: Boolean get() = serverUrl.isNotBlank()

    /** `""` when SSH isn't set up. */
    var sshHost: String
        get() = prefs.getString(KEY_SSH_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SSH_HOST, value).apply()

    /** SSH host and user are both set (Setup, or Settings with SSH filled in). */
    val hasSsh: Boolean get() = isSshConfigured(sshHost, sshUser)

    var sshPort: Int
        get() = prefs.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)
        set(value) = prefs.edit().putInt(KEY_SSH_PORT, value).apply()

    var sshUser: String
        get() = prefs.getString(KEY_SSH_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SSH_USER, value).apply()

    var stUserDir: String
        get() = prefs.getString(KEY_ST_USER_DIR, DEFAULT_ST_USER_DIR) ?: DEFAULT_ST_USER_DIR
        set(value) = prefs.edit().putString(KEY_ST_USER_DIR, value).apply()

    /** The SillyTavern folder on the server; `""` = find it automatically. */
    var stDir: String
        get() = prefs.getString(KEY_ST_DIR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ST_DIR, value).apply()

    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /** The notification / battery prompts were shown once at startup (Setup and the switch ask again). */
    var keepAlivePermissionsAsked: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE_ASKED, value).apply()

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var knownHostKey: String?
        get() = prefs.getString(KEY_KNOWN_HOST_KEY, null)
        set(value) = prefs.edit().putString(KEY_KNOWN_HOST_KEY, value).apply()

    var stThemeJson: String?
        get() = prefs.getString(KEY_ST_THEME_JSON, null)
        set(value) = prefs.edit().putString(KEY_ST_THEME_JSON, value).apply()

    /** Debug-only A/B switch; callers must also check BuildConfig.DEBUG. */
    val debugDisableAssetCache: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_DISABLE_ASSET_CACHE, false)

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_ST_USER_DIR = "default-user"

        private const val KEY_DEBUG_DISABLE_ASSET_CACHE = "debug_disable_asset_cache"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USER = "ssh_user"
        private const val KEY_ST_USER_DIR = "st_user_dir"
        private const val KEY_ST_DIR = "st_dir"
        private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val KEY_KEEP_ALIVE_ASKED = "keep_alive_permissions_asked"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_KNOWN_HOST_KEY = "known_host_key"
        private const val KEY_ST_THEME_JSON = "st_theme_json"

        fun isSshConfigured(host: String, user: String): Boolean = host.isNotBlank() && user.isNotBlank()

        fun isValidServerUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return false
            }
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        }
    }
}

/**
 * Control Center, Stats and Logs run over SSH: they show Setup (not a connection error) until Setup
 * is done and SSH is still configured (it can be cleared in Settings afterwards).
 */
fun sshScreensReady(setupComplete: Boolean, hasSsh: Boolean): Boolean = setupComplete && hasSsh

/**
 * The address to save for an install set up before the app stopped having a built-in one: set up,
 * no saved URL, so it ran on the old default, which was its SSH host on port 8000. Null otherwise.
 */
fun legacyServerUrl(hasSavedUrl: Boolean, setupComplete: Boolean, sshHost: String): String? {
    val host = sshHost.trim()
    if (hasSavedUrl || !setupComplete || host.isEmpty()) return null
    val url = if (':' in host) "http://[$host]:8000" else "http://$host:8000"
    return url.takeIf { AppSettings.isValidServerUrl(it) }
}
