package dev.halftoast.stmobile.panel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the exact commands the app sends for status, start, the dashboard and stop (the command
 * line as `<name>.cmd`, its script as `<name>.stdin`) to build/linux-smoke, for
 * tools/test-stpanel-linux.sh to run under dash in its containers (Debian's and Termux's sh).
 */
class ActionScriptExport {
    @Test fun exportsTheAppsCommandsForTheLinuxSmokeTest() {
        val stpanel = File("../server/stpanel").readText()
        val stDir = "~/My Apps/SillyTavern"
        val commands = mapOf(
            "action-status" to Probes.actionWith(stpanel, "status", stDir, null),
            "action-start" to Probes.actionWith(stpanel, "start", stDir, null),
            "dashboard" to Probes.dashboardWith(File("src/main/assets/probes/dashboard.sh").readText(), stDir, "default-user"),
            "action-stop" to Probes.actionWith(stpanel, "stop", stDir, null),
        )
        val out = File("build/linux-smoke").apply { deleteRecursively(); mkdirs() }
        commands.forEach { (name, c) ->
            assertTrue(c.command, Probes.isSafeCommand(c.command))
            File(out, "$name.cmd").writeText(c.command)
            File(out, "$name.stdin").writeText(c.stdin!!)
        }
    }
}
