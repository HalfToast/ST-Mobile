package dev.halftoast.stmobile.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
        deleteTree(root.toPath())
    }

    /** Deletes [dir] without following symlinks (node_modules/yaml links to a real checkout). */
    private fun deleteTree(dir: Path) {
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun makeTree(dir: File) {
        File(dir, "data/default-user/characters").mkdirs()
        File(dir, "server.js").writeText("// fake\n")
        File(dir, "config.yaml").writeText("port: 8123\nwhitelistMode: false\n")
    }

    /**
     * Writes its args next to the server.js it was given, then idles until TERM. It re-executes
     * itself with argv[0] `node`, so the process looks like a real node (stpanel checks argv[0]).
     */
    private fun fakeNode(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            #!/bin/bash
            case "${'$'}*" in *server.js*) ;; *) exit 0 ;; esac
            for a in "${'$'}@"; do last=${'$'}a; done
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > "${'$'}(dirname "${'$'}last")/node.args"
            exec -a node /bin/bash -c 'trap "kill \${'$'}s 2>/dev/null; exit 0" TERM INT; while :; do sleep 1 & s=${'$'}!; wait ${'$'}s; done' node "${'$'}@"
            """.trimIndent() + "\n",
        )
        file.setExecutable(true)
    }

    private data class Result(val code: Int, val out: String, val err: String)

    private fun run(
        vararg args: String,
        stDirValue: String? = stDir.path,
        path: String = "${bin.path}:/usr/bin:/bin",
        stdin: String = "",
        env: Map<String, String> = emptyMap(),
        cwd: File = root,
    ): Result {
        val pb = ProcessBuilder(listOf("/bin/bash", script.path) + args).directory(cwd)
        pb.environment().apply {
            clear()
            put("PATH", path)
            put("HOME", home.path)
            // Keep the machine's own node (Homebrew, /usr/local) out of the search.
            put("STPANEL_NODE_DIRS", "")
            if (stDirValue != null) put("ST_DIR", stDirValue)
            putAll(env)
        }
        val p = pb.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
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

    @Test fun ignoresProcessesThatOnlyMentionNode() {
        // argv[0] is a shell, not node, although the args say "node" and "server.js" and the cwd fits.
        val p = ProcessBuilder("/bin/sh", "-c", "while :; do sleep 1; done", "nodemon", "server.js")
            .directory(stDir).start()
        started += p
        p.outputStream.close()
        Thread.sleep(300)
        val status = run("status")
        assertTrue(status.out, status.out.contains("OFFLINE"))
        assertEquals(0, run("stop").code)
        assertTrue(p.isAlive)
    }

    // ---- whitelist (real node and the yaml package) ----

    private val config get() = File(stDir, "config.yaml")
    private val wlConfig = """
        # SillyTavern settings
        port: 8123
        # Network
        whitelistMode: true
        whitelist:
          - 127.0.0.1
        # keep this comment
        listen: false
    """.trimIndent() + "\n"

    /** PATH holding the machine's real node; skips the test when node or yaml can't be found. */
    private fun whitelistSetup(): String {
        val node = realNode
        assumeTrue("whitelist tests need node (not found)", node != null)
        val yaml = yamlPackage
        assumeTrue("whitelist tests need the yaml package (not under ~/Developer/*/node_modules, npm install failed)", yaml != null)
        val nodeBin = File(root, "realnode").apply { mkdirs() }
        Files.createSymbolicLink(File(nodeBin, "node").toPath(), node!!.toPath())
        File(stDir, "node_modules").mkdirs()
        Files.createSymbolicLink(File(stDir, "node_modules/yaml").toPath(), yaml!!.absoluteFile.toPath())
        config.writeText(wlConfig)
        return "${nodeBin.path}:/usr/bin:/bin"
    }

    @Test fun menuAddsAnAddress() {
        val path = whitelistSetup()
        val r = run("whitelist", path = path, stdin = "2\n192.168.1.20\n")
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("Added"))
        assertTrue(r.out, r.out.contains("127.0.0.1")) // the list shown first
        assertFalse(r.out, r.out.contains("\u001b["))
        val text = config.readText()
        assertTrue(text, Regex("""(?m)^\s*-\s*192\.168\.1\.20\s*$""").containsMatchIn(text))
        assertTrue(text, text.contains("# keep this comment"))
        assertTrue(text, text.contains("# SillyTavern settings"))
        assertTrue(text, text.contains("whitelistMode: true"))
    }

    @Test fun menuTogglesMode() {
        val path = whitelistSetup()
        val r = run("whitelist", path = path, stdin = "1\n")
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("toggled to: DISABLED"))
        assertTrue(config.readText().contains("whitelistMode: false"))
        assertTrue(config.readText().contains("# keep this comment"))
    }

    @Test fun injectionIsRejected() {
        val path = whitelistSetup()
        val r = run("whitelist", path = path, stdin = "2\n1.2.3.4\"); require(\"fs\").writeFileSync(\"pwned\",\"x\"); (\"\n")
        assertTrue(r.out + r.err, r.code != 0 || (r.out + r.err).contains("not an IP address"))
        assertEquals(wlConfig, config.readText())
        assertFalse(root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.any { it.name == "pwned" })
        assertFalse(File("pwned").exists())
    }

    @Test fun addAcceptsCidrAndIpv6() {
        val path = whitelistSetup()
        for (a in listOf("10.0.0.0/8", "fd00::1", "100.101.102.103")) {
            val r = run("whitelist", "add", a, path = path)
            assertEquals("$a: ${r.err}", 0, r.code)
            assertTrue(r.out, r.out.contains("Added"))
        }
        val text = config.readText()
        assertTrue(text, text.contains("- 10.0.0.0/8"))
        assertTrue(text, text.contains("fd00::1"))
        assertTrue(text, text.contains("- 100.101.102.103"))
    }

    @Test fun addRejectsWhatIsNotAnAddress() {
        val path = whitelistSetup()
        for (a in listOf("256.1.1.1", "1.2.3", "10.0.0.0/33", "1.2.3.4/", "fd00::1/129", "1:2:3::4::5", "localhost", "01.2.3.4", "")) {
            val r = run("whitelist", "add", a, path = path)
            assertNotEquals("'$a' was accepted", 0, r.code)
        }
        assertEquals(wlConfig, config.readText())
    }

    @Test fun modeIsAbsolute() {
        val path = whitelistSetup()
        repeat(2) {
            val r = run("whitelist", "mode", "on", path = path)
            assertEquals(r.err, 0, r.code)
            assertTrue(config.readText().contains("whitelistMode: true"))
        }
        assertEquals(0, run("whitelist", "mode", "off", path = path).code)
        assertTrue(config.readText().contains("whitelistMode: false"))
        assertNotEquals(0, run("whitelist", "mode", "maybe", path = path).code)
    }

    // ---- backup, clean, stats, update, system info ----

    private fun tarList(archive: File): List<String> = sh("tar", "-tzf", archive.path).lines().filter { it.isNotBlank() }

    @Test fun backupNamesAndContents() {
        File(stDir, "data/default-user/characters/Seraphina.png").writeText("png")
        File(stDir, "data/default-user/backups").mkdirs()
        File(stDir, "data/default-user/backups/chat_old.jsonl").writeText("{}")
        val r = run("backup")
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("Backup successfully created"))
        assertTrue(r.out, Regex("""Archive Size:\s*\S+""").containsMatchIn(r.out))
        val archives = File(stDir, "backups").listFiles()!!.toList()
        assertEquals(1, archives.size)
        assertTrue(archives[0].name, Regex("""st_backup_\d{8}_\d{6}\.tar\.gz""").matches(archives[0].name))
        val entries = tarList(archives[0])
        assertTrue("$entries", entries.any { it.startsWith("data/default-user/characters/Seraphina.png") })
        assertTrue("$entries", entries.contains("config.yaml"))
        assertFalse("$entries", entries.any { it.startsWith("data/default-user/backups") })

        File(stDir, "data/alice/chats").mkdirs()
        File(stDir, "data/alice/chats/x.jsonl").writeText("{}")
        val again = run("backup", env = mapOf("ST_USER" to "alice"))
        assertEquals(again.err, 0, again.code)
        val newest = File(stDir, "backups").listFiles()!!.maxByOrNull { it.lastModified() }!!
        val aliceEntries = tarList(newest)
        assertTrue("$aliceEntries", aliceEntries.any { it.startsWith("data/alice/chats/x.jsonl") })
        assertFalse("$aliceEntries", aliceEntries.any { it.startsWith("data/default-user") })
    }

    @Test fun backupFailsWithoutTheUserFolder() {
        val r = run("backup", env = mapOf("ST_USER" to "nobody"))
        assertNotEquals(0, r.code)
        assertFalse(File(stDir, "backups").listFiles()?.isNotEmpty() ?: false)
    }

    @Test fun cleanAsksFirst() {
        val cache = File(stDir, "data/_cache").apply { mkdirs() }
        File(cache, "x").writeText("cached")
        val no = run("clean", stdin = "n\n")
        assertEquals(no.err, 0, no.code)
        assertTrue(File(cache, "x").exists())
        assertFalse(no.out, no.out.contains("Cache successfully cleared"))
        val yes = run("clean", stdin = "y\n")
        assertEquals(yes.err, 0, yes.code)
        assertTrue(yes.out, yes.out.contains("Cache successfully cleared"))
        assertTrue(cache.isDirectory)
        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }

    @Test fun statusShowsBatteryOnlyWhenKnown() {
        val offline = run("status")
        assertEquals(0, run("start").code)
        val online = run("status")
        for (r in listOf(offline, online)) {
            assertEquals(r.err, 0, r.code)
            assertFalse(r.out, r.out.lines().any { Regex("""Battery:\s*%""").containsMatchIn(it) })
            assertFalse(r.out, r.out.contains("N/A%"))
        }
        // Where pmset reports a battery (a MacBook), status shows its level.
        val pmset = runCatching { sh("pmset", "-g", "batt") }.getOrDefault("")
        if (Regex("""\d+%;""").containsMatchIn(pmset)) {
            assertTrue(offline.out, Regex("""Battery: \d+%""").containsMatchIn(offline.out))
        }
    }

    @Test fun statsListsCharactersInPlainText() {
        assumeTrue("stats needs python3", File("/usr/bin/python3").canExecute())
        File(stDir, "data/default-user/characters/Seraphina.png").writeText("png")
        val r = run("stats")
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("Seraphina"))
        assertTrue(r.out, r.out.contains("Last Active"))
        assertFalse(r.out, r.out.contains("\u001b["))
    }

    @Test fun statsTakesTheUserFolderFromTheEnvironment() {
        assumeTrue("stats needs python3", File("/usr/bin/python3").canExecute())
        // Quotes in the folder name must not break (or get into) the Python source.
        val user = "o'neil \"x\""
        File(stDir, "data/$user/characters").mkdirs()
        File(stDir, "data/$user/characters/Bob.png").writeText("png")
        val r = run("stats", env = mapOf("ST_USER" to user))
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("Bob"))
    }

    @Test fun updateSaysWhenNotAGitCheckout() {
        val r = run("update")
        assertNotEquals(0, r.code)
        assertTrue(r.out + r.err, (r.out + r.err).contains("git"))
    }

    @Test fun helpNamesTheTermuxFolder() {
        val r = run("help")
        assertTrue(r.out, r.out.contains("/data/data/com.termux/files/home/SillyTavern"))
        assertTrue(r.out, r.out.contains("whitelist add"))
    }

    companion object {
        /** The machine's real node, for the whitelist tests. */
        val realNode: File? by lazy {
            val dirs = (System.getenv("PATH") ?: "").split(':') +
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", System.getProperty("user.home") + "/.local/share/fnm/aliases/default/bin")
            dirs.filter { it.isNotEmpty() }.map { File(it, "node") }.firstOrNull { it.isFile && it.canExecute() }
        }

        /** A yaml package: one under ~/Developer/<project>/node_modules, else installed into build/test-node. */
        val yamlPackage: File? by lazy {
            File(System.getProperty("user.home"), "Developer").listFiles()?.sorted()
                ?.map { File(it, "node_modules/yaml") }
                ?.firstOrNull { File(it, "package.json").isFile }
                ?.let { return@lazy it }
            val local = File("build/test-node/node_modules/yaml")
            if (!File(local, "package.json").isFile) {
                val npm = realNode?.let { File(it.parentFile, "npm") }?.takeIf { it.canExecute() }
                if (npm != null) {
                    runCatching {
                        ProcessBuilder(npm.path, "install", "--prefix", "build/test-node", "yaml")
                            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
                            .waitFor(120, TimeUnit.SECONDS)
                    }
                }
            }
            local.takeIf { File(it, "package.json").isFile }
        }
    }
}
