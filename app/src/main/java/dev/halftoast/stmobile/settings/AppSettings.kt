package dev.halftoast.stmobile.settings

import android.content.SharedPreferences
import dev.halftoast.stmobile.nav.FabEdge
import dev.halftoast.stmobile.nav.FabPosition
import java.net.URI

class AppSettings(private val prefs: SharedPreferences) {

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var sshHost: String
        get() = prefs.getString(KEY_SSH_HOST, DEFAULT_SSH_HOST) ?: DEFAULT_SSH_HOST
        set(value) = prefs.edit().putString(KEY_SSH_HOST, value).apply()

    var sshPort: Int
        get() = prefs.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT)
        set(value) = prefs.edit().putInt(KEY_SSH_PORT, value).apply()

    var sshUser: String
        get() = prefs.getString(KEY_SSH_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SSH_USER, value).apply()

    var stUserDir: String
        get() = prefs.getString(KEY_ST_USER_DIR, DEFAULT_ST_USER_DIR) ?: DEFAULT_ST_USER_DIR
        set(value) = prefs.edit().putString(KEY_ST_USER_DIR, value).apply()

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

    var fabPosition: FabPosition
        get() {
            val d = FabPosition.Default
            val edge = FabEdge.entries.firstOrNull { it.name == prefs.getString(KEY_FAB_EDGE, null) } ?: d.edge
            val fraction = prefs.getFloat(KEY_FAB_FRACTION, d.fraction)
                .coerceIn(FabPosition.MIN_FRACTION, FabPosition.MAX_FRACTION)
            return FabPosition(edge, fraction)
        }
        set(value) = prefs.edit().putString(KEY_FAB_EDGE, value.edge.name).putFloat(KEY_FAB_FRACTION, value.fraction).apply()

    /** Debug-only A/B switch; callers must also check BuildConfig.DEBUG. */
    val debugDisableAssetCache: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_DISABLE_ASSET_CACHE, false)

    companion object {
        const val DEFAULT_SERVER_URL = "http://100.101.102.103:8000"
        const val DEFAULT_SSH_HOST = "100.101.102.103"
        const val DEFAULT_SSH_PORT = 8022
        const val DEFAULT_ST_USER_DIR = "default-user"

        private const val KEY_DEBUG_DISABLE_ASSET_CACHE = "debug_disable_asset_cache"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USER = "ssh_user"
        private const val KEY_ST_USER_DIR = "st_user_dir"
        private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val KEY_KEEP_ALIVE_ASKED = "keep_alive_permissions_asked"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_KNOWN_HOST_KEY = "known_host_key"
        private const val KEY_ST_THEME_JSON = "st_theme_json"
        private const val KEY_FAB_EDGE = "fab_edge"
        private const val KEY_FAB_FRACTION = "fab_fraction"

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
