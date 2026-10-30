package dev.halftoast.stmobile.panel

data class DashboardState(
    val online: Boolean,
    val pid: Int?,
    val uptime: String?,
    val httpCode: String?,
    val latencyMs: Int?,
    val cpuPercent: String?,
    val ramMb: Int?,
    val batteryLevel: Int?,
    val batteryStatus: String?,
    val diskFree: String?,
    val diskUsedPercent: String?,
    val cacheSize: String?,
    val branch: String,
    val commit: String,
    val behind: Int,
    val ahead: Int,
    val characters: Int,
    val chats: Int,
    val worlds: Int,
    val personas: Int,
    val backups: Int,
    val lastClient: String?,
    val recentLogs: List<String>,
    /** The SillyTavern user folder the counts were read from. */
    val userDir: String? = null,
    /** That folder doesn't exist on the tablet, so the counts are meaningless (all zero). */
    val userDirMissing: Boolean = false,
    /** The stpanel subcommand of an app-started action still running on the tablet, if any. */
    val runningAction: String? = null,
)

data class CharacterStat(val name: String, val messages: Int, val lastActiveEpochSec: Long, val avatarFile: String? = null)

data class WhitelistState(val enabled: Boolean, val entries: List<String>)

sealed interface ActionResult {
    val message: String
    data class Ok(override val message: String) : ActionResult
    data class Failed(override val message: String) : ActionResult
    /** The app stopped waiting (timeout) but the detached command keeps running on the tablet. */
    data class StillRunning(override val message: String) : ActionResult
}
