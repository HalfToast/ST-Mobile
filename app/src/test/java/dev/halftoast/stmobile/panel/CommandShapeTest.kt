package dev.halftoast.stmobile.panel

import dev.halftoast.stmobile.ssh.KeyInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Every command line the app sends parses the same in bash, zsh and fish: `env NAME='value'… <interpreter> -s|-`. */
class CommandShapeTest {
    private val stpanel = File("../server/stpanel").readText()
    private val goodDirs = listOf("", "~/SillyTavern", "/srv/My Apps/SillyTavern")
    private val subcommands = PanelAction.entries.map { it.subcommand } + listOf("status", "whitelist")

    private fun all(stDir: String): List<RemoteCommand> =
        listOf(
            Probes.dashboardWith("# dashboard.sh", stDir, "default-user"),
            Probes.statsWith("# stats.py", stDir, "default-user"),
            Probes.whitelistWith("# whitelist.sh", stDir),
            Probes.tail(LogKind.Server, stDir),
            Probes.tail(LogKind.Access, stDir),
        ) + subcommands.map { Probes.actionWith(stpanel, it, stDir, "y\n") }

    @Test fun everyCommandIsSafe() {
        for (dir in goodDirs) {
            for (c in all(dir)) assertTrue(c.command, Probes.isSafeCommand(c.command))
        }
        assertTrue(Probes.isSafeCommand(KeyInstaller.INSTALL_COMMAND))
    }

    @Test fun commandsCarryTheFolders() {
        assertEquals("env ST_USER='default-user' bash -s", Probes.dashboardWith("x", "", "default-user").command)
        assertEquals(
            "env ST_DIR='/srv/My Apps/SillyTavern' ST_USER='default-user' python3 -",
            Probes.statsWith("x", "/srv/My Apps/SillyTavern", "default-user").command,
        )
        assertEquals("env ST_DIR='~/SillyTavern' bash -s", Probes.whitelistWith("x", "~/SillyTavern").command)
        assertEquals("env sh -s", Probes.actionWith(stpanel, "start", "", null).command)
        assertEquals("env ST_DIR='~/SillyTavern' sh -s", Probes.tail(LogKind.Server, "~/SillyTavern").command)
    }

    @Test fun badFoldersAreRejected() {
        val bad = listOf("/a'b", "/a\\b", "/a\$b", "/a`b`", "/a\"b", "/a\nb", "SillyTavern", "~SillyTavern")
        for (dir in bad) {
            val builders: List<() -> RemoteCommand> = listOf(
                { Probes.dashboardWith("x", dir, "default-user") },
                { Probes.statsWith("x", dir, "default-user") },
                { Probes.whitelistWith("x", dir) },
                { Probes.tail(LogKind.Access, dir) },
                { Probes.actionWith(stpanel, "status", dir, null) },
            )
            builders.forEach { build ->
                try {
                    build()
                    fail("accepted ${dir.replace("\n", "\\n")}")
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        try { Probes.dashboardWith("x", "", "a'b; rm -rf /"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun safeCommandShape() {
        assertTrue(Probes.isSafeCommand("echo ok"))
        assertTrue(Probes.isSafeCommand("env bash -s"))
        assertFalse(Probes.isSafeCommand("bash -s"))
        assertFalse(Probes.isSafeCommand("env ST_DIR='a'b' bash -s"))
        assertFalse(Probes.isSafeCommand("env ST_DIR='a\\' bash -s"))
        assertFalse(Probes.isSafeCommand("env ST_DIR='a\nb' bash -s"))
        assertFalse(Probes.isSafeCommand("env st_dir='a' bash -s"))
        assertFalse(Probes.isSafeCommand("env bash -c 'id'"))
        assertFalse(Probes.isSafeCommand("env bash -s; id"))
    }
}
