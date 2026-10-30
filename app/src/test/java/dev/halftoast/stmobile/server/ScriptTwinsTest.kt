package dev.halftoast.stmobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The probe scripts ship separately from stpanel (no shared include), so they carry verbatim copies
 * of its process-discovery, system-info and node-finding functions. Each copy must match stpanel's.
 */
class ScriptTwinsTest {
    private val stpanel = File("../server/stpanel").readText()
    private val dashboard = File("src/main/assets/probes/dashboard.sh").readText()
    private val whitelist = File("src/main/assets/probes/whitelist.sh").readText()

    /** The lines from `name() {` to the first `}` at the start of a line, as `sed '/^name() {/,/^}/p'`. */
    private fun function(script: String, name: String, file: String): String {
        val lines = script.lines()
        val start = lines.indexOfFirst { it.startsWith("$name() {") }
        val end = lines.withIndex().indexOfFirst { (i, line) -> i > start && line.startsWith("}") }
        assertTrue("$name() not found in $file", start >= 0 && end > start)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun assertTwins(name: String, copy: String, file: String, adjust: (String) -> String = { it }) =
        assertEquals("$name() in $file differs from server/stpanel's", adjust(function(stpanel, name, "server/stpanel")), function(copy, name, file))

    @Test fun dashboardCopiesMatchStpanel() {
        for (name in listOf(
            "pid_alive", "proc_args", "proc_argv0", "proc_cwd", "candidate_pids", "is_st_server",
            "pidfile_pid", "get_port", "find_tool", "battery_info",
        )) {
            assertTwins(name, dashboard, "dashboard.sh")
        }
    }

    @Test fun whitelistCopiesMatchStpanel() {
        assertTwins("found_node", whitelist, "whitelist.sh")
        // The one known difference: the probe isn't stpanel, so its message has no "stpanel:" prefix.
        assertTwins("find_node", whitelist, "whitelist.sh") {
            val message = "echo \"stpanel: node not found"
            assertEquals(1, Regex(Regex.escape(message)).findAll(it).count())
            it.replace(message, "echo \"node not found")
        }
    }
}
