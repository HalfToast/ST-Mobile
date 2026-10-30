package dev.halftoast.stmobile.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs [Probes.wrapDetached] under a real `sh`, standing in for the tablet's sshd: the process's
 * stdin/stdout/stderr pipes play the SSH channel.
 */
class DetachedActionTest {
    private lateinit var tmp: File
    private lateinit var work: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("tmpdir").toFile()
        work = Files.createTempDirectory("work").toFile()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
        work.deleteRecursively()
    }

    private fun start(cmd: String, marker: String? = null): Process =
        ProcessBuilder("sh", "-c", Probes.wrapDetached(cmd, marker)).apply { environment()["TMPDIR"] = tmp.path }.start()

    private val marks get() = File(tmp, Probes.ACTION_MARKS)

    /** Runs the real dashboard.sh against [work] as home; returns its output. */
    private fun dashboard(): String {
        val script = File("src/main/assets/probes/dashboard.sh")
        val p = ProcessBuilder("bash", script.absolutePath).apply {
            environment()["TMPDIR"] = tmp.path
            environment()["HOME"] = work.path
            redirectErrorStream(true)
        }.start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        return out
    }

    private fun dashboardAction(): String? =
        dashboard().lineSequence().firstOrNull { it.startsWith("action=") }?.removePrefix("action=")

    @Test fun passesStdinOutputAndExitCodeThrough() {
        val p = start("cat; echo oops >&2; exit 3")
        p.outputStream.use { it.write("y\n".toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals("y\n", out)
        assertEquals("oops\n", err)
        assertEquals(3, p.exitValue())
        assertEquals(emptyList<String>(), tmp.list()!!.toList())
    }

    @Test fun missingCommandStillExits127() {
        val p = start("no-such-command-st-mobile")
        p.outputStream.close()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(127, p.exitValue())
    }

    @Test fun aMarkedActionIsMarkedUntilItEnds() {
        val go = File(work, "go")
        val p = start("while [ ! -e '${go.path}' ]; do sleep 0.05; done; echo done", marker = "update")
        p.outputStream.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (marks.list().orEmpty().none { !it.endsWith(".w") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val mark = marks.listFiles()!!.single()
        assertTrue(mark.readText().trim().endsWith(" update"))
        assertEquals("update", dashboardAction())
        go.createNewFile()
        assertEquals("done\n", p.inputStream.bufferedReader().readText())
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), marks.list()!!.toList())
        assertEquals(null, dashboardAction())
    }

    @Test fun theDashboardDropsAMarkWhoseProcessIsGone() {
        marks.mkdirs()
        val dead = ProcessBuilder("sh", "-c", "echo \$\$").start().let { it.waitFor(); it.inputStream.bufferedReader().readText().trim() }
        File(marks, "tmp.stale").writeText("$dead update\n")
        assertEquals(null, dashboardAction())
        assertEquals(emptyList<String>(), marks.list()!!.toList())
    }

    @Test fun theDashboardReusesItsSlowLinesUntilAnActionEnds() {
        val chars = File(work, "SillyTavern/data/default-user/characters").apply { mkdirs() }
        fun count() = dashboard().lineSequence().first { it.startsWith("chars=") }
        assertEquals("chars=0", count())
        File(chars, "a.png").createNewFile()
        assertEquals("reused within the TTL", "chars=0", count())
        val p = start("true", marker = "clean")
        p.outputStream.close()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals("fresh after an action", "chars=1", count())
    }

    /** The app timed out or died: the channel closes while the command still has output to write. */
    @Test fun commandFinishesAfterChannelCloses() {
        val done = File(work, "done")
        val p = start("sleep 1; i=0; while [ \$i -lt 5000 ]; do echo line \$i; echo warn \$i >&2; i=\$((i+1)); done; touch '${done.path}'")
        p.outputStream.close()
        p.inputStream.close()
        p.errorStream.close()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertTrue("command was killed before finishing", done.exists())
        assertEquals(emptyList<String>(), tmp.list()!!.toList())
    }
}
