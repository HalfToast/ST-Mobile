package dev.halftoast.stmobile.panel

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]")

/** Removes ANSI escape sequences, including the leading clear (ESC[H ESC[2J ESC[3J) stpanel may emit. */
fun stripAnsi(s: String): String = ANSI.replace(s, "")

private fun keyValues(raw: String): List<Pair<String, String>> =
    stripAnsi(raw).lineSequence().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trimEnd('\r')
    }.toList()

fun parseDashboard(raw: String): DashboardState {
    val kv = keyValues(raw)
    fun get(k: String): String? = kv.firstOrNull { it.first == k }?.second?.trim()?.ifEmpty { null }
    fun int(k: String): Int? = get(k)?.toIntOrNull()
    return DashboardState(
        online = get("online") == "1",
        pid = get("pid")?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull(),
        uptime = get("etime"),
        httpCode = get("http_code"),
        latencyMs = get("latency_s")?.toDoubleOrNull()?.let { Math.round(it * 1000).toInt() },
        cpuPercent = get("cpu"),
        ramMb = int("rss_kb")?.let { it / 1024 },
        batteryLevel = int("bat_level"),
        batteryStatus = get("bat_status"),
        diskFree = get("disk_free"),
        diskUsedPercent = get("disk_used_pct"),
        cacheSize = get("cache_size"),
        branch = get("branch") ?: "unknown",
        commit = get("commit") ?: "unknown",
        behind = int("behind") ?: 0,
        ahead = int("ahead") ?: 0,
        characters = int("chars") ?: 0,
        chats = int("chats") ?: 0,
        worlds = int("worlds") ?: 0,
        personas = int("personas") ?: 0,
        backups = int("backups") ?: 0,
        lastClient = get("last_client"),
        recentLogs = kv.filter { it.first == "log" }.map { it.second }.take(3),
        userDir = get("user_dir"),
        userDirMissing = get("user_dir_missing") == "1",
        runningAction = get("action"),
    )
}

/** Shown instead of the inventory counts when the dashboard reports `user_dir_missing=1`. */
fun userDirMissingMessage(userDir: String?): String =
    "SillyTavern user folder '${userDir ?: "?"}' not found — check Settings"

fun parseStats(raw: String): List<CharacterStat> =
    stripAnsi(raw).lineSequence().mapNotNull { line ->
        val p = line.trimEnd('\r').split('\t')
        if (p.size != 3 && p.size != 4) return@mapNotNull null
        val m = p[1].trim().toIntOrNull() ?: return@mapNotNull null
        val t = p[2].trim().toLongOrNull() ?: return@mapNotNull null
        CharacterStat(p[0], m, t, p.getOrNull(3)?.trim()?.ifEmpty { null })
    }.toList().sortedByDescending { it.lastActiveEpochSec }

fun parseWhitelist(raw: String): WhitelistState {
    val kv = keyValues(raw)
    return WhitelistState(
        enabled = kv.firstOrNull { it.first == "mode" }?.second?.trim() == "true",
        entries = kv.filter { it.first == "entry" }.map { it.second.trim() }.filter { it.isNotEmpty() },
    )
}

/** What stpanel's whitelist menu said to an add (option 2). */
enum class WhitelistAdd { Added, AlreadyListed }

/** Null when stpanel neither added the address nor found it already there (it failed). */
fun parseWhitelistAdd(raw: String): WhitelistAdd? {
    val clean = stripAnsi(raw)
    return when {
        "Added" in clean -> WhitelistAdd.Added
        "is already in the whitelist" in clean -> WhitelistAdd.AlreadyListed
        else -> null
    }
}

fun parseBackupResult(raw: String): ActionResult {
    val clean = stripAnsi(raw)
    val size = Regex("""Archive Size:\s*(\S+)""").find(clean)?.groupValues?.get(1)
    return if (clean.contains("Backup successfully created") && size != null) {
        ActionResult.Ok("Backup created, $size")
    } else {
        ActionResult.Failed("Backup failed")
    }
}

fun parseCleanResult(raw: String): ActionResult =
    if (stripAnsi(raw).contains("Cache successfully cleared")) ActionResult.Ok("Cache cleared")
    else ActionResult.Failed("Cache not cleared")

fun parseUpdateResult(raw: String, exitCode: Int?): ActionResult {
    val clean = stripAnsi(raw)
    if (clean.contains("CONFLICT") || clean.contains("error:") || clean.contains("fatal:")) {
        return ActionResult.Failed(
            clean.lineSequence().firstOrNull { "CONFLICT" in it || "error:" in it || "fatal:" in it }?.trim()
                ?: "Update failed",
        )
    }
    if (exitCode != null && exitCode != 0) return ActionResult.Failed("Update failed (exit $exitCode)")
    if (clean.contains("Already up to date")) return ActionResult.Ok("Already up to date")
    val sha = Regex("""Updating\s+\S+\.\.(\w+)""").find(clean)?.groupValues?.get(1)
    return ActionResult.Ok(if (sha != null) "Updated to ${sha.take(9)}" else "Updated")
}

fun parseStatusOnline(raw: String): Boolean = Regex("""\bONLINE\b""").containsMatchIn(stripAnsi(raw))
