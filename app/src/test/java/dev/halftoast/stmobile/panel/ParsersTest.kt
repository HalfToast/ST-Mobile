package dev.halftoast.stmobile.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParsersTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private val clear = "\u001B[H\u001B[2J\u001B[3J"

    @Test fun stripsAnsi() = assertEquals("ONLINE", stripAnsi("\u001B[1;32mONLINE\u001B[0m"))
    @Test fun stripsClearSequences() = assertEquals("x", stripAnsi("${clear}x"))

    @Test fun dashboardOnline() {
        val d = parseDashboard(fixture("dashboard-online.txt"))
        assertTrue(d.online); assertNotNull(d.pid)
        assertEquals(3, d.recentLogs.size.coerceAtMost(3))
        assertEquals("staging", d.branch)
        assertNotNull(d.ramMb); assertNotNull(d.latencyMs); assertEquals("200", d.httpCode)
        assertEquals(84, d.batteryLevel)
    }

    @Test fun dashboardOffline() {
        val d = parseDashboard(fixture("dashboard-offline.txt"))
        assertFalse(d.online); assertNull(d.pid)
    }

    @Test fun dashboardToleratesMissingKeys() {
        val d = parseDashboard("online=1\nbranch=staging\n")
        assertTrue(d.online); assertEquals(0, d.behind); assertNull(d.batteryLevel)
    }

    @Test fun dashboardRunningAction() {
        assertEquals("update", parseDashboard("online=1\naction=update\n").runningAction)
        assertNull(parseDashboard("online=1\n").runningAction)
    }

    @Test fun dashboardTakesFirstPid() = assertEquals(12, parseDashboard("online=1\npid=12\n34\n").pid)
    @Test fun dashboardFirstPidWithNewlineValue() = assertEquals(12, parseDashboard("online=1\npid=12\n").pid)

    @Test fun dashboardWithLeadingClear() {
        val d = parseDashboard(clear + fixture("dashboard-online.txt"))
        assertTrue(d.online)
    }

    @Test fun dashboardUserDirMissing() {
        val d = parseDashboard("online=1\npid=12\nuser_dir=typo-user\nuser_dir_missing=1\nchars=0\nchats=0\n")
        assertTrue(d.userDirMissing)
        assertEquals("typo-user", d.userDir)
        assertEquals("SillyTavern user folder 'typo-user' not found — check Settings", userDirMissingMessage(d.userDir))
    }

    @Test fun dashboardUserDirPresent() {
        val d = parseDashboard(fixture("dashboard-online.txt") + "user_dir=default-user\n")
        assertFalse(d.userDirMissing)
        assertEquals("default-user", d.userDir)
    }

    @Test fun backupOk() {
        val r = parseBackupResult(fixture("stpanel-backup.txt"))
        assertTrue(r is ActionResult.Ok)
        assertTrue((r as ActionResult.Ok).message.matches(Regex("Backup created, .+")))
    }

    @Test fun backupWithLeadingClear() = assertEquals(ActionResult.Ok("Backup created, 12M"), parseBackupResult(clear + fixture("stpanel-backup.txt")))
    @Test fun backupFailed() = assertTrue(parseBackupResult("\u001B[1;31m✗ Backup failed.\u001B[0m") is ActionResult.Failed)
    @Test fun cleanOk() = assertEquals(ActionResult.Ok("Cache cleared"), parseCleanResult(fixture("stpanel-clean.txt")))
    @Test fun cleanWithLeadingClear() = assertEquals(ActionResult.Ok("Cache cleared"), parseCleanResult(clear + fixture("stpanel-clean.txt")))
    @Test fun cleanCancelled() = assertEquals(ActionResult.Failed("Cache not cleared"), parseCleanResult("Operation cancelled."))
    @Test fun updateUpToDate() = assertEquals(ActionResult.Ok("Already up to date"), parseUpdateResult(fixture("stpanel-update-uptodate.txt"), 0))
    @Test fun updateWithLeadingClear() = assertEquals(ActionResult.Ok("Already up to date"), parseUpdateResult(clear + fixture("stpanel-update-uptodate.txt"), 0))
    @Test fun updateConflictFails() = assertTrue(parseUpdateResult("CONFLICT (content): Merge conflict in public/script.js\nerror: could not apply", 0) is ActionResult.Failed)
    @Test fun updateFastForward() = assertEquals(ActionResult.Ok("Updated to def5678"), parseUpdateResult("Updating abc1234..def5678\nFast-forward\n 1 file changed", 0))
    @Test fun updateUnknown() = assertEquals(ActionResult.Ok("Updated"), parseUpdateResult("Successfully rebased and updated refs/heads/staging.", 0))
    @Test fun updateNonZeroExit() = assertTrue(parseUpdateResult("something", 1) is ActionResult.Failed)

    @Test fun statsAll() {
        val s = parseStats(fixture("stats.tsv"))
        assertTrue(s.isNotEmpty()); assertEquals(s.sortedByDescending { it.lastActiveEpochSec }, s)
    }

    @Test fun statsSkipsGarbage() {
        val s = parseStats("A\t1\t5\nbroken\nB\tx\t2\nC\t3\t9\n")
        assertEquals(listOf("C", "A"), s.map { it.name })
    }

    @Test fun whitelist() {
        val w = parseWhitelist("mode=true\nentry=127.0.0.1\nentry=100.64.0.0/10\n")
        assertEquals(WhitelistState(true, listOf("127.0.0.1", "100.64.0.0/10")), w)
    }

    @Test fun statusOnline() {
        assertTrue(parseStatusOnline("\u001B[1;32mONLINE\u001B[0m"))
        assertTrue(parseStatusOnline(clear + "SillyTavern: \u001B[1;32mONLINE\u001B[0m (PID: 1)"))
        assertFalse(parseStatusOnline("SillyTavern: \u001B[1;31mOFFLINE\u001B[0m | Port: 8000"))
    }

    @Test fun probesRejectBadDir() {
        try { Probes.dashboardWith("s", "", "a'b; rm -rf /"); fail() } catch (_: IllegalArgumentException) {}
        val c = Probes.dashboardWith("script", "", "default-user")
        assertEquals(RemoteCommand("env ST_USER='default-user' bash -s", "script"), c)
    }

    @Test fun actionCommands() {
        assertEquals("stpanel start", unwrapDetached(Probes.actionWith("# stpanel", "start", "", null).stdin!!))
        assertEquals("y\n", detachedInput(Probes.actionWith("# stpanel", "clean", "", "y\n").stdin!!))
        assertEquals(null, detachedInput(Probes.actionWith("# stpanel", "status", "", null).stdin!!))
    }

    @Test fun whitelistAdd() {
        assertEquals(WhitelistAdd.Added, parseWhitelistAdd("\u001B[1;32m✓ Added 192.168.1.20 to whitelist!\u001B[0m\n(Note: Restart SillyTavern for config changes to take effect)\n"))
        assertEquals(WhitelistAdd.AlreadyListed, parseWhitelistAdd("192.168.1.20 is already in the whitelist.\n"))
        assertEquals(null, parseWhitelistAdd("stpanel: not an IP address\n"))
    }

    @Test fun statsWithAvatarColumn() {
        val s = parseStats("Seraphina\t77\t1793250000\tSeraphina.png\nDante\t1\t1793000000\t\n")
        assertEquals(CharacterStat("Seraphina", 77, 1793250000, "Seraphina.png"), s[0]); assertNull(s[1].avatarFile)
        assertEquals(1, parseStats("Old\t3\t5\n").size) // 3-field lines still parse
    }
}
