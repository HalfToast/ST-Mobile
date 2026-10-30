package dev.halftoast.stmobile.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs [Probes.wrapDetached] scripts under a real `sh -s`, standing in for the server's sshd: the
 * process's stdin/stdout/stderr pipes play the SSH channel.
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

    /** Starts the wrapper the way sshd runs `sh -s`: the script on stdin, then stdin closed. */
    private fun start(cmd: String, marker: String? = null, input: String? = null): Process =
        ProcessBuilder("sh", "-s").apply { environment()["TMPDIR"] = tmp.path }.start().also { p ->
            p.outputStream.use { it.write(Probes.wrapDetached(cmd, marker, input).toByteArray()) }
        }

    private class Run(val out: String, val err: String, val exit: Int)

    /** Runs [c] as a login shell would: `<shell> -c "<command>"` with [c]'s stdin. */
    private fun runAs(shell: List<String>, c: RemoteCommand, path: String): Run {
        val p = ProcessBuilder(shell + c.command).apply {
            environment()["TMPDIR"] = tmp.path
            environment()["HOME"] = work.path
            environment()["PATH"] = path
        }.start()
        p.outputStream.use { out -> c.stdin?.let { out.write(it.toByteArray()) } }
        val err = StringBuilder()
        val errReader = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        val out = p.inputStream.bufferedReader().readText()
        errReader.join()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        return Run(out, err.toString(), p.exitValue())
    }

    private val basePath = "/usr/bin:/bin"

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
        val p = start("cat; echo oops >&2; exit 3", input = "y\n")
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
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(127, p.exitValue())
    }

    @Test fun aMarkedActionIsMarkedUntilItEnds() {
        val go = File(work, "go")
        val p = start("while [ ! -e '${go.path}' ]; do sleep 0.05; done; echo done", marker = "update")
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
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals("fresh after an action", "chars=1", count())
    }

    /** The app timed out or died: the channel closes while the command still has output to write. */
    @Test fun commandFinishesAfterChannelCloses() {
        val done = File(work, "done")
        val p = start("sleep 1; i=0; while [ \$i -lt 5000 ]; do echo line \$i; echo warn \$i >&2; i=\$((i+1)); done; touch '${done.path}'")
        p.inputStream.close()
        p.errorStream.close()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertTrue("command was killed before finishing", done.exists())
        assertEquals(emptyList<String>(), tmp.list()!!.toList())
    }

    @Test fun filesAndInputArriveVerbatim() {
        val tricky = "line 'one' \$HOME `x`\nSTM_EOF_0\nSTM_EOF_1\n\\ end\n"
        val p = ProcessBuilder("sh", "-s").apply { environment()["TMPDIR"] = tmp.path }.start()
        p.outputStream.use { it.write(Probes.wrapDetached("cat \"\$d/f\"; cat", null, tricky, mapOf("f" to tricky)).toByteArray()) }
        assertEquals(tricky + tricky, p.inputStream.bufferedReader().readText())
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
    }

    /** sshd runs the command line through the user's login shell; zsh must see the same thing sh does. */
    @Test fun runsUnderZshToo() {
        assumeTrue(File("/bin/zsh").canExecute())
        val fake = "echo \"sub=\$1 dir=\$ST_DIR\"; cat; echo warn >&2; exit 4\n"
        val c = Probes.actionWith(fake, "status", "/srv/My Apps/SillyTavern", "y\n")
        val sh = runAs(listOf("sh", "-c"), c, basePath)
        val zsh = runAs(listOf("/bin/zsh", "-c"), c, basePath)
        assertEquals("sub=status dir=/srv/My Apps/SillyTavern\ny\n", sh.out)
        assertEquals("warn\n", sh.err)
        assertEquals(4, sh.exit)
        assertEquals(sh.out, zsh.out)
        assertEquals(sh.err, zsh.err)
        assertEquals(sh.exit, zsh.exit)
    }

    @Test fun builtInStpanelRunsWhenNoneInstalled() {
        val stDir = File(work, "My Apps/SillyTavern").apply { mkdirs() }
        File(stDir, "server.js").writeText("// fake\n")
        File(stDir, "config.yaml").writeText("port: 8123\n")
        val c = Probes.actionWith(File("../server/stpanel").readText(), "status", stDir.path, null)
        val builtIn = runAs(listOf("sh", "-c"), c, basePath)
        assertTrue(builtIn.out + builtIn.err, builtIn.out.contains("OFFLINE"))
        assertEquals(builtIn.err, 0, builtIn.exit)

        val bin = File(work, "bin").apply { mkdirs() }
        File(bin, "stpanel").apply { writeText("#!/bin/sh\necho installed\n"); setExecutable(true) }
        val installed = runAs(listOf("sh", "-c"), c, "${bin.path}:$basePath")
        assertEquals("installed\n", installed.out)
        assertEquals(0, installed.exit)
    }
}
