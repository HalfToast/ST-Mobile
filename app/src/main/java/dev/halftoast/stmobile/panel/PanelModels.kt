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
)

data class CharacterStat(val name: String, val messages: Int, val lastActiveEpochSec: Long)

data class WhitelistState(val enabled: Boolean, val entries: List<String>)

sealed interface ActionResult {
    data class Ok(val message: String) : ActionResult
    data class Failed(val message: String) : ActionResult
}
