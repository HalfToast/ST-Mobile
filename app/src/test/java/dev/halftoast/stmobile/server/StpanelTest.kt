package dev.halftoast.stmobile.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the distributable `server/stpanel` under the machine's `/bin/bash` (3.2 on macOS) against a
 * fake SillyTavern tree whose path holds a space, with a fake `node` that idles until killed.
 * Everything it starts lives under [root]; [tearDown] kills whatever is left there.
 */
class StpanelTest {
    private lateinit var root: File
    private lateinit var home: File
    private lateinit var bin: File
    private lateinit var stDir: File
    private val script = File("../server/stpanel").absoluteFile
    private val started = mutableListOf<Process>()

    private val pidFile get() = File(stDir, "sillytavern.pid")
    private val stopFile get() = File(stDir, ".stop_requested")
    private val logFile get() = File(stDir, "sillytavern.log")
    private val argsFile get() = File(stDir, "node.args")

    @Before fun setUp() {
        root = Files.createTempDirectory("stpanel").toFile()
        home = File(root, "home").apply { mkdirs() }
        bin = File(root, "bin").apply { mkdirs() }
        stDir = File(root, "My Apps/SillyTavern")
        makeTree(stDir)
        fakeNode(File(bin, "node"))
    }

    @After fun tearDown() {
        try {
            run("stop")
        } catch (_: Throwable) {
        }
        started.forEach { it.destroyForcibly() }
        processesWith(root.path).forEach { sh("kill", "-9", "$it") }
        root.deleteRecursively()
    }

    private fun makeTree(dir: File) {
        File(dir, "data/default-user/characters").mkdirs()
        File(dir, "server.js").writeText("// fake\n")
        File(dir, "config.yaml").writeText("port: 8123\nwhitelistMode: false\n")
    }

    /** Writes its args next to the server.js it was given, then idles until TERM. */
    private fun fakeNode(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            #!/bin/sh
            case "${'$'}*" in *server.js*) ;; *) exit 0 ;; esac
            for a in "${'$'}@"; do last=${'$'}a; done
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > "${'$'}(dirname "${'$'}last")/node.args"
            trap 'kill ${'$'}s 2>/dev/null; exit 0' TERM INT
            while :; do sleep 1 & s=${'$'}!; wait ${'$'}s; done
            """.trimIndent() + "\n",
        )
        file.setExecutable(true)
    }

    private data class Result(val code: Int, val out: String, val err: String)

    private fun run(
        vararg args: String,
        stDirValue: String? = stDir.path,
        path: String = "${bin.path}:/usr/bin:/bin",
    ): Result {
        val pb = ProcessBuilder(listOf("/bin/bash", script.path) + args)
        pb.environment().apply {
            clear()
            put("PATH", path)
            put("HOME", home.path)
            // Keep the machine's own node (Homebrew, /usr/local) out of the search.
            put("STPANEL_NODE_DIRS", "")
            if (stDirValue != null) put("ST_DIR", stDirValue)
        }
        val p = pb.start()
        p.outputStream.close()
        val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
        val err = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().readText() }
        assertTrue("stpanel ${args.joinToString(" ")} did not finish", p.waitFor(40, TimeUnit.SECONDS))
        // A detached supervisor that kept stdout/stderr open would hang the app's SSH channel.
        return Result(p.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS))
    }

    /** Runs a short command and returns its stdout. */
    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return out
    }

    /** Alive and not a zombie. */
    private fun alive(pid: Long): Boolean = sh("ps", "-p", "$pid", "-o", "stat=").trim().let { it.isNotEmpty() && !it.contains('Z') }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    /** Pids of live processes whose args hold every [needle] (callers only pass paths inside [root]). */
    private fun processesWith(vararg needle: String): List<Long> =
        sh("ps", "-A", "-ww", "-o", "pid=", "-o", "stat=", "-o", "args=").lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && needle.all { line.contains(it) } }
            .map { it.split(Regex("\\s+"), limit = 3) }
            .filter { !it[1].contains('Z') }
            .map { it[0].toLong() }
            .toList()

    /** A fake node started by hand (not by stpanel) from [cwd]; returns the process and its pid. */
    private fun launchDirectly(cwd: File, vararg args: String): Pair<Process, Long> {
        val p = ProcessBuilder(listOf("/bin/sh", "-c", "echo \$\$; exec \"\$0\" \"\$@\" >/dev/null 2>&1", File(bin, "node").path) + args)
            .directory(cwd).start()
        started += p
        p.outputStream.close()
        val pid = p.inputStream.bufferedReader().readLine().trim().toLong()
        return p to pid
    }

    @Test fun startWritesPidAndLogAndStopRemovesThem() {
        val start = run("start")
        assertEquals(start.err, 0, start.code)
        val pid = pidFile.readText().trim().toLong()
        assertTrue(alive(pid))
        val log = logFile.readText()
        assertTrue(log.contains("=== ["))
        assertTrue(log.contains("Starting SillyTavern"))
        waitUntil("node.args") { argsFile.exists() && argsFile.readText().contains("server.js") }
        val args = argsFile.readText()
        assertTrue(args.contains("--max-old-space-size=2048"))
        assertTrue(args.contains("server.js"))

        val stop = run("stop")
        assertEquals(stop.err, 0, stop.code)
        assertFalse(alive(pid))
        assertFalse(pidFile.exists())
        assertFalse(stopFile.exists())
        assertEquals(emptyList<Long>(), processesWith("Watchdog reviving in 2 seconds", stDir.path))
    }

    @Test fun stopFindsAServerStartedByTheOriginalScripts() {
        // The original startst runs `node --max-old-space-size=2048 server.js` from ST_DIR and
        // writes the pid file.
        val (p, pid) = launchDirectly(stDir, "--max-old-space-size=2048", "server.js")
        pidFile.writeText("$pid\n")
        waitUntil("node.args") { argsFile.exists() }
        val stop = run("stop")
        assertEquals(stop.err, 0, stop.code)
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertFalse(pidFile.exists())
    }

    @Test fun stopFindsAServerByItsFolderWithoutAPidFile() {
        val (p, pid) = launchDirectly(stDir, "--max-old-space-size=2048", "server.js")
        waitUntil("node.args") { argsFile.exists() }
        val status = run("status")
        assertTrue(status.out, status.out.contains("PID: $pid"))
        assertEquals(0, run("stop").code)
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
    }

    @Test fun stopStopsTheOriginalSupervisorLoop() {
        // startst's supervisor body, with its ST_DIR pasted in the way startst does it.
        val loop = """
            trap "" HUP SIGHUP
            ST_DIR="${stDir.path}"
            LOG_FILE="${'$'}ST_DIR/sillytavern.log"
            STOP_FILE="${'$'}ST_DIR/.stop_requested"
            PID_FILE="${'$'}ST_DIR/sillytavern.pid"
            cd "${'$'}ST_DIR" || exit 1
            while [ ! -f "${'$'}STOP_FILE" ]; do
                node --max-old-space-size=2048 server.js >> "${'$'}LOG_FILE" 2>&1 &
                NODE_PID=${'$'}!
                echo "${'$'}NODE_PID" > "${'$'}PID_FILE"
                wait "${'$'}NODE_PID"
                if [ -f "${'$'}STOP_FILE" ]; then rm -f "${'$'}STOP_FILE" "${'$'}PID_FILE"; break; fi
                echo "Watchdog reviving in 2 seconds..." >> "${'$'}LOG_FILE"
                sleep 2
            done
        """.trimIndent()
        val sup = ProcessBuilder("/bin/bash", "-c", loop).apply {
            environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
            redirectErrorStream(true)
            redirectOutput(File("/dev/null"))
        }.start()
        started += sup
        waitUntil("pid file") { pidFile.exists() && pidFile.readText().isNotBlank() }
        val node = pidFile.readText().trim().toLong()
        assertEquals(0, run("stop").code)
        assertTrue(sup.waitFor(10, TimeUnit.SECONDS))
        assertFalse(alive(node))
    }

    @Test fun leavesServersOfOtherFoldersAlone() {
        val other = File(root, "Other/SillyTavern").apply { makeTree(this) }
        val (p, _) = launchDirectly(other, "--max-old-space-size=2048", "server.js")
        val (q, _) = launchDirectly(root, "--max-old-space-size=2048", File(other, "server.js").path)
        waitUntil("node.args") { File(other, "node.args").exists() }
        val status = run("status")
        assertTrue(status.out, status.out.contains("OFFLINE"))
        assertEquals(0, run("stop").code)
        assertTrue(p.isAlive)
        assertTrue(q.isAlive)
    }

    @Test fun restartLeavesExactlyOneServer() {
        assertEquals(0, run("start").code)
        val first = pidFile.readText().trim().toLong()
        val restart = run("restart")
        assertEquals(restart.err, 0, restart.code)
        val second = pidFile.readText().trim().toLong()
        assertNotEquals(first, second)
        assertFalse(alive(first))
        val servers = processesWith("${stDir.path}/server.js")
        assertEquals(listOf(second), servers)
        assertEquals(1, processesWith("Watchdog reviving in 2 seconds", stDir.path).size)
    }

    @Test fun startWhileTheSupervisorRevivesLeavesOneServer() {
        assertEquals(0, run("start").code)
        sh("kill", "-9", pidFile.readText().trim())
        val again = run("start")
        assertEquals(again.err, 0, again.code)
        Thread.sleep(3_000) // past the watchdog's 2 s revive
        assertEquals(1, processesWith("${stDir.path}/server.js").size)
        assertEquals(1, processesWith("Watchdog reviving in 2 seconds", stDir.path).size)
    }

    @Test fun findsNodeFromNvmWhenNotOnPath() {
        val nvmBin = File(home, ".nvm/versions/node/v20.0.0/bin")
        fakeNode(File(nvmBin, "node"))
        File(home, ".nvm/nvm.sh").writeText("export PATH=\"${nvmBin.path}:\$PATH\"\n")
        val start = run("start", path = "/usr/bin:/bin")
        assertEquals(start.err, 0, start.code)
        assertTrue(alive(pidFile.readText().trim().toLong()))
        waitUntil("node.args") { argsFile.exists() }
    }

    @Test fun saysWhenNodeIsMissing() {
        val start = run("start", path = "/usr/bin:/bin")
        assertNotEquals(0, start.code)
        assertTrue(start.err, start.err.contains("node"))
        assertFalse(pidFile.exists())
    }

    @Test fun saysWhenTheFolderIsMissing() {
        val status = run("status", stDirValue = File(root, "nowhere").path)
        assertNotEquals(0, status.code)
        assertTrue(status.err, status.err.contains("SillyTavern folder not found"))
    }

    @Test fun tildeStDirIsExpanded() {
        makeTree(File(home, "SillyTavern"))
        val status = run("status", stDirValue = "~/SillyTavern")
        assertEquals(status.err, 0, status.code)
        assertTrue(status.out, status.out.contains("8123"))
    }

    @Test fun defaultsToSillyTavernInHome() {
        makeTree(File(home, "SillyTavern"))
        val status = run("status", stDirValue = null)
        assertEquals(status.err, 0, status.code)
        assertTrue(status.out, status.out.contains("Port: 8123"))
    }

    @Test fun plainOutputWhenNotATerminal() {
        for (cmd in listOf("status", "help")) {
            val r = run(cmd)
            assertEquals(r.err, 0, r.code)
            assertFalse("$cmd: ${r.out}", r.out.contains("\u001b["))
        }
    }

    @Test fun panelKeepsRefreshingUntilInputEnds() {
        val pb = ProcessBuilder("/bin/bash", script.path).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PATH", "${bin.path}:/usr/bin:/bin")
            put("HOME", home.path)
            put("ST_DIR", stDir.path)
            put("STPANEL_REFRESH", "1")
        }
        val p = pb.start()
        started += p
        val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
        // Several 1 s refresh timeouts pass with stdin still open.
        assertFalse("panel exited on a refresh timeout", p.waitFor(3_500, TimeUnit.MILLISECONDS))
        p.outputStream.close()
        assertTrue("panel did not exit at end of input", p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        val shown = out.get(5, TimeUnit.SECONDS)
        assertTrue(Regex("CONTROL CENTER").findAll(shown).count() >= 3)
    }

    @Test fun statusOfflineThenOnline() {
        val before = run("status")
        assertEquals(before.err, 0, before.code)
        assertTrue(before.out, before.out.contains("OFFLINE"))
        assertFalse(before.out.contains("ONLINE"))
        assertEquals(0, run("start").code)
        val pid = pidFile.readText().trim()
        val after = run("status")
        assertTrue(after.out, after.out.contains("ONLINE"))
        assertTrue(after.out, after.out.contains("PID: $pid"))
    }
}
