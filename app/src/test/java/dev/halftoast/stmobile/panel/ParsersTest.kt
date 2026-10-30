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

    @Test fun dashboardStDirMissing() {
        val d = parseDashboard("online=0\nst_dir_missing=1\nst_dir=/home/me/SillyTavern\nuser_dir=default-user\nuser_dir_missing=1\n")
        assertTrue(d.stDirMissing)
        assertEquals("/home/me/SillyTavern", d.stDir)
        assertEquals(
            "SillyTavern wasn't found in /home/me/SillyTavern — check the SillyTavern folder in Settings",
            stDirMissingMessage(d.stDir),
        )
        assertFalse(parseDashboard(fixture("dashboard-online.txt")).stDirMissing)
    }

    @Test fun stDirMissingFromScriptMessages() {
        assertEquals("/srv/st", stDirMissingIn("SillyTavern folder not found: /srv/st\n"))
        // stpanel's own wording, with its hint for terminal use.
        assertEquals(
            "/srv/my st",
            stDirMissingIn("stpanel: SillyTavern folder not found: /srv/my st (set ST_DIR to the folder that holds server.js)\n"),
        )
        assertNull(stDirMissingIn("Traceback (most recent call last):\n"))
    }

    @Test fun backupOk() {
        val r = parseBackupResult(fixture("stpanel-backup.txt"))
        assertTrue(r is ActionResult.Ok)
        assertTrue((r as ActionResult.Ok).message.matches(Regex("Backup created, .+")))
    }

    @Test fun backupWithLeadingClear() = assertEquals(ActionResult.Ok("Backup created, 12M"), parseBackupResult(clear + fixture("stpanel-backup.txt")))
    @Test fun backupFailed() = assertEquals(ActionResult.Failed("Backup failed"), parseBackupResult("\u001B[1;31m✗ Backup failed.\u001B[0m"))
    @Test fun backupFailedSaysWhy() {
        assertEquals(
            ActionResult.Failed("Backup failed: user folder data/typo-user not found (set ST_USER)"),
            parseBackupResult("=== SillyTavern Quick Backup ===\n\n\u001B[1;31m✗ Backup failed: user folder data/typo-user not found (set ST_USER).\u001B[0m\n"),
        )
        assertEquals(
            ActionResult.Failed("Backup failed: SillyTavern wasn't found in /srv/st — check the SillyTavern folder in Settings"),
            parseBackupResult("\nstpanel: SillyTavern folder not found: /srv/st (set ST_DIR to the folder that holds server.js)\n"),
        )
    }
    @Test fun cleanOk() = assertEquals(ActionResult.Ok("Cache cleared"), parseCleanResult(fixture("stpanel-clean.txt")))
    @Test fun cleanWithLeadingClear() = assertEquals(ActionResult.Ok("Cache cleared"), parseCleanResult(clear + fixture("stpanel-clean.txt")))
    @Test fun cleanCancelled() = assertEquals(ActionResult.Failed("Cache not cleared"), parseCleanResult("Operation cancelled."))
    @Test fun updateUpToDate() = assertEquals(ActionResult.Ok("Already up to date"), parseUpdateResult(fixture("stpanel-update-uptodate.txt"), 0))
    @Test fun updateWithLeadingClear() = assertEquals(ActionResult.Ok("Already up to date"), parseUpdateResult(clear + fixture("stpanel-update-uptodate.txt"), 0))
    @Test fun updateConflictFails() = assertTrue(parseUpdateResult("CONFLICT (content): Merge conflict in public/script.js\nerror: could not apply", 0) is ActionResult.Failed)
    @Test fun updateFastForward() = assertEquals(ActionResult.Ok("Updated to def5678"), parseUpdateResult("Updating abc1234..def5678\nFast-forward\n 1 file changed", 0))
    @Test fun updateUnknown() = assertEquals(ActionResult.Ok("Updated"), parseUpdateResult("Successfully rebased and updated refs/heads/staging.", 0))
    @Test fun updateNonZeroExit() = assertEquals(ActionResult.Failed("Update failed (exit 1)"), parseUpdateResult("something", 1))
    @Test fun updateFailedSaysWhy() {
        assertEquals(
            ActionResult.Failed("Update failed: git not found; install git to update SillyTavern"),
            parseUpdateResult("=== Updating SillyTavern (git pull) ===\n\nstpanel: git not found; install git to update SillyTavern\n", 1),
        )
        assertEquals(
            ActionResult.Failed("Update failed: /srv/st is not a git checkout, so it can't be updated with git pull"),
            parseUpdateResult("\nstpanel: /srv/st is not a git checkout, so it can't be updated with git pull\n", 1),
        )
        assertEquals(
            ActionResult.Failed("Update failed: SillyTavern wasn't found in /srv/st — check the SillyTavern folder in Settings"),
            parseUpdateResult("\nstpanel: SillyTavern folder not found: /srv/st (set ST_DIR to the folder that holds server.js)\n", 1),
        )
        // A failed npm install after the pull: stpanel's line wins over npm's own error lines.
        assertEquals(
            ActionResult.Failed("Update failed: npm install failed (exit 1); run it in the SillyTavern folder before starting"),
            parseUpdateResult(
                "Updating abc1234..def5678\nFast-forward\nnpm error: ETARGET\n" +
                    "stpanel: npm install failed (exit 1); run it in the SillyTavern folder before starting\n",
                1,
            ),
        )
    }

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
