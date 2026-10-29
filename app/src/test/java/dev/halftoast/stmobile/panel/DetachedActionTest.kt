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

    private fun start(cmd: String): Process =
        ProcessBuilder("sh", "-c", Probes.wrapDetached(cmd)).apply { environment()["TMPDIR"] = tmp.path }.start()

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
