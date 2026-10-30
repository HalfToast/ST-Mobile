package dev.halftoast.stmobile.panel

import dev.halftoast.stmobile.server.StpanelTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the probe scripts in assets/probes the way the app does (`env … bash -s` / `python3 -`, the
 * script on stdin) under the machine's `/bin/bash` (3.2 on macOS) and python3, against a fake tree at
 * `$HOME/My Apps/SillyTavern` named as `~/My Apps/SillyTavern`. Every process they start lives under
 * [root]; [tearDown] kills whatever is left there.
 */
class ProbeScriptsTest {
    private lateinit var root: File
    private lateinit var home: File
    private lateinit var tmp: File
    private lateinit var bin: File
    private lateinit var stDir: File
    private val started = mutableListOf<Process>()
    private val stDirValue = "~/My Apps/SillyTavern"
    private val probes = File("src/main/assets/probes").absoluteFile

    @Before fun setUp() {
        root = Files.createTempDirectory("probes").toFile()
        home = File(root, "home")
        tmp = File(root, "tmp").apply { mkdirs() }
        bin = File(root, "bin").apply { mkdirs() }
        stDir = File(home, "My Apps/SillyTavern")
        File(stDir, "data/default-user/characters").mkdirs()
        File(stDir, "server.js").writeText("// fake\n")
        File(stDir, "config.yaml").writeText("port: 8123\nwhitelistMode: false\n")
        StpanelTest.fakeNode(File(bin, "node"))
    }

    @After fun tearDown() {
        started.forEach { it.destroyForcibly() }
        sh("ps", "-A", "-ww", "-o", "pid=", "-o", "args=").lineSequence()
            .map { it.trim() }
            .filter { it.contains(root.path) }
            .forEach { sh("kill", "-9", it.substringBefore(' ')) }
        // Files.walk doesn't follow the node_modules/yaml symlink into the real package.
        Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private data class Result(val code: Int, val out: String, val err: String)

    /** Runs [interpreter] with [script] on stdin, a clean environment and [env]. */
    private fun runProbe(
        interpreter: List<String>,
        script: String,
        env: Map<String, String?> = mapOf("ST_DIR" to stDirValue, "ST_USER" to "default-user"),
        path: String = "${bin.path}:/usr/bin:/bin",
    ): Result {
        val pb = ProcessBuilder(interpreter).directory(root)
        pb.environment().apply {
            clear()
            put("PATH", path)
            put("HOME", home.path)
            put("TMPDIR", tmp.path)
            put("STPANEL_NODE_DIRS", "")
            env.forEach { (k, v) -> if (v != null) put(k, v) }
        }
        val p = pb.start()
        p.outputStream.use { it.write(File(probes, script).readBytes()) }
        val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
        val err = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().readText() }
        assertTrue("$script did not finish", p.waitFor(30, TimeUnit.SECONDS))
        return Result(p.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS))
    }

    private fun dashboard(env: Map<String, String?> = mapOf("ST_DIR" to stDirValue, "ST_USER" to "default-user")): Map<String, String> {
        // A fresh look every time: no reuse of the slow lines from an earlier run.
        File(tmp, Probes.DASHBOARD_CACHE).delete()
        val r = runProbe(listOf("/bin/bash", "-s"), "dashboard.sh", env)
        assertEquals(r.err, 0, r.code)
        val lines = r.out.lines().filter { it.isNotEmpty() }
        assertTrue(r.out, lines.all { Regex("[a-z_]+=.*").matches(it) })
        return lines.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return out
    }

    /** The fake node started by hand from [cwd] with [args]; returns its pid. */
    private fun launchNode(cwd: File, vararg args: String): Long {
        val p = ProcessBuilder(listOf("/bin/sh", "-c", "echo \$\$; exec \"\$0\" \"\$@\" >/dev/null 2>&1", File(bin, "node").path) + args)
            .directory(cwd).start()
        started += p
        p.outputStream.close()
        val pid = p.inputStream.bufferedReader().readLine().trim().toLong()
        // Until its re-exec as `node`, the fake node is bash and rightly not counted as a server.
        val deadline = System.currentTimeMillis() + 10_000
        while (!StpanelTest.isNode(pid)) {
            assertTrue("fake node did not become node", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
        return pid
    }

    // ---- dashboard.sh

    @Test fun dashboardOfflineInAFolderWithASpace() {
        File(stDir, "data/default-user/characters/Alice.png").writeText("png")
        val d = dashboard()
        assertEquals("0", d["online"])
        assertEquals("1", d["chars"])
        assertEquals(null, d["user_dir_missing"])
        assertEquals(null, d["st_dir_missing"])
        assertEquals(null, d["st_dir"])
        assertTrue("$d", d["disk_free"]!!.isNotBlank())
        assertTrue("$d", d["disk_used_pct"]!!.endsWith("%"))
        // A battery only where there is one (a MacBook's pmset), never an empty or N/A level.
        val pmset = runCatching { sh("pmset", "-g", "batt") }.getOrDefault("")
        if (Regex("""\d+%;""").containsMatchIn(pmset)) {
            assertTrue("$d", Regex("\\d+").matches(d["bat_level"]!!))
        } else {
            assertFalse("$d", d.containsKey("bat_level"))
        }
        // Not inside a git checkout: nothing borrowed from a repo the folder happens to sit in.
        assertEquals("unknown", d["branch"])
    }

    @Test fun dashboardOnlineWithAServerStartedFromTheFolder() {
        val pid = launchNode(stDir, "--max-old-space-size=2048", "server.js")
        val d = dashboard()
        assertEquals("$d", "1", d["online"])
        assertEquals("$pid", d["pid"])
        assertTrue("$d", d["etime"]!!.isNotBlank())
    }

    @Test fun dashboardOnlineFromTheValidatedPidFile() {
        val pid = launchNode(root, "--max-old-space-size=2048", File(stDir, "server.js").path)
        File(stDir, "sillytavern.pid").writeText("$pid\n")
        val d = dashboard()
        assertEquals("$d", "1", d["online"])
        assertEquals("$pid", d["pid"])
    }

    @Test fun dashboardIgnoresOtherFoldersServersAndStalePidFiles() {
        val other = File(root, "Other/SillyTavern").apply { mkdirs() }
        File(other, "server.js").writeText("// fake\n")
        launchNode(other, "--max-old-space-size=2048", "server.js")
        // Started from this folder, but with another folder's absolute server.js: not ours.
        val elsewhere = launchNode(stDir, "--max-old-space-size=2048", File(other, "server.js").path)
        // A pid file naming a process that isn't this folder's server.
        File(stDir, "sillytavern.pid").writeText("$elsewhere\n")
        val d = dashboard()
        assertEquals("$d", "0", d["online"])
        assertEquals(null, d["pid"])
    }

    @Test fun dashboardDefaultsToSillyTavernInHome() {
        val def = File(home, "SillyTavern/data/default-user/characters").apply { mkdirs() }
        File(def, "a.png").writeText("png")
        File(def, "b.png").writeText("png")
        assertEquals("2", dashboard(mapOf("ST_USER" to "default-user"))["chars"])
    }

    @Test fun dashboardFlagsAMissingUserFolder() {
        val d = dashboard(mapOf("ST_DIR" to stDirValue, "ST_USER" to "nobody"))
        assertEquals("1", d["user_dir_missing"])
        assertEquals("nobody", d["user_dir"])
    }

    @Test fun dashboardNamesAMissingSillyTavernFolder() {
        val d = dashboard(mapOf("ST_DIR" to "~/Nowhere/SillyTavern", "ST_USER" to "default-user"))
        assertEquals("1", d["st_dir_missing"])
        assertEquals(File(home, "Nowhere/SillyTavern").path, d["st_dir"])
        assertEquals("0", d["online"])
    }

    // ---- log tails (Probes.tail)

    /** The first line the tail command prints, then it is stopped (tail -F never ends). */
    private fun firstTailLine(kind: LogKind, stDirSetting: String): String {
        val tail = Probes.tail(kind, stDirSetting)
        assertTrue(tail.command, tail.command.endsWith(" sh -s"))
        val pb = ProcessBuilder("/bin/sh", "-s").directory(root)
        pb.environment().apply {
            clear()
            put("PATH", "/usr/bin:/bin")
            put("HOME", home.path)
            if (stDirSetting.isNotEmpty()) put("ST_DIR", stDirSetting)
        }
        val p = pb.redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        started += p
        p.outputStream.use { it.write(tail.stdin!!.toByteArray()) }
        val line = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
        p.destroy()
        return line
    }

    @Test fun tailSaysWhenTheLogFileIsMissing() {
        val line = firstTailLine(LogKind.Server, stDirValue)
        assertEquals("${Probes.TAIL_NO_FILE}${File(stDir, "sillytavern.log").path}", line)
        assertEquals("${Probes.TAIL_NO_FILE}${File(stDir, "data/access.log").path}", firstTailLine(LogKind.Access, stDirValue))
    }

    @Test fun tailSaysWhenTheSillyTavernFolderIsMissing() {
        assertEquals("${Probes.TAIL_NO_ST_DIR}${File(home, "Nowhere").path}", firstTailLine(LogKind.Server, "~/Nowhere"))
    }

    @Test fun tailShowsAnExistingLog() {
        File(stDir, "sillytavern.log").writeText("first line\n")
        assertEquals("first line", firstTailLine(LogKind.Server, stDirValue))
    }

    // ---- stats.py

    @Test fun statsSaysWhenTheSillyTavernFolderIsMissing() {
        assumeTrue("stats needs python3", File("/usr/bin/python3").canExecute())
        val r = runProbe(listOf("/usr/bin/python3", "-"), "stats.py", mapOf("ST_DIR" to "~/Nowhere", "ST_USER" to "default-user"))
        assertNotEquals(0, r.code)
        assertEquals("", r.out)
        assertEquals(File(home, "Nowhere").path, stDirMissingIn(r.err))
    }

    @Test fun statsListsACharacter() {
        assumeTrue("stats needs python3", File("/usr/bin/python3").canExecute())
        File(stDir, "data/default-user/characters/Alice.png").writeText("png")
        val r = runProbe(listOf("/usr/bin/python3", "-"), "stats.py")
        assertEquals(r.err, 0, r.code)
        assertEquals("Alice\t0\t0\tAlice.png\n", r.out)
        assertEquals(listOf(CharacterStat("Alice", 0, 0, "Alice.png")), parseStats(r.out))
    }

    @Test fun statsDefaultsToSillyTavernInHome() {
        assumeTrue("stats needs python3", File("/usr/bin/python3").canExecute())
        File(home, "SillyTavern/data/default-user/characters").mkdirs()
        File(home, "SillyTavern/server.js").writeText("// fake\n")
        File(home, "SillyTavern/data/default-user/characters/Bob.png").writeText("png")
        val r = runProbe(listOf("/usr/bin/python3", "-"), "stats.py", mapOf("ST_USER" to "default-user"))
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.startsWith("Bob\t"))
    }

    // ---- whitelist.sh (real node and the yaml package)

    private val wlConfig = "port: 8123\nwhitelistMode: true\nwhitelist:\n  - 127.0.0.1\n"

    /** Links the machine's node into [nodeDir] and yaml into the tree; skips without either. */
    private fun whitelistSetup(nodeDir: File) {
        val node = StpanelTest.realNode
        assumeTrue("whitelist tests need node (not found)", node != null)
        val yaml = StpanelTest.yamlPackage
        assumeTrue("whitelist tests need the yaml 2.x package", yaml != null)
        nodeDir.mkdirs()
        Files.createSymbolicLink(File(nodeDir, "node").toPath(), node!!.toPath())
        File(stDir, "node_modules").mkdirs()
        Files.createSymbolicLink(File(stDir, "node_modules/yaml").toPath(), yaml!!.absoluteFile.toPath())
        File(stDir, "config.yaml").writeText(wlConfig)
    }

    @Test fun whitelistReadsModeAndEntriesInAFolderWithASpace() {
        val nodeDir = File(root, "realnode")
        whitelistSetup(nodeDir)
        val r = runProbe(listOf("/bin/bash", "-s"), "whitelist.sh", mapOf("ST_DIR" to stDirValue), path = "${nodeDir.path}:/usr/bin:/bin")
        assertEquals(r.err, 0, r.code)
        assertEquals("mode=true\nentry=127.0.0.1\n", r.out)
        assertEquals(WhitelistState(true, listOf("127.0.0.1")), parseWhitelist(r.out))
        assertEquals(wlConfig, File(stDir, "config.yaml").readText())
    }

    /** Folder names reach node through process.env only: a quote in one can't break (or get into) the JS. */
    @Test fun whitelistTakesAnyFolderName() {
        val nodeDir = File(root, "realnode")
        whitelistSetup(nodeDir)
        val odd = File(home, "it's \"odd\"\$(touch pwned)")
        assertTrue(stDir.renameTo(odd))
        val r = runProbe(listOf("/bin/bash", "-s"), "whitelist.sh", mapOf("ST_DIR" to odd.path), path = "${nodeDir.path}:/usr/bin:/bin")
        assertEquals(r.err, 0, r.code)
        assertEquals("mode=true\nentry=127.0.0.1\n", r.out)
        assertFalse(root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.any { it.name == "pwned" })
    }

    @Test fun whitelistFindsNodeFromNvmWhenNotOnPath() {
        whitelistSetup(File(home, ".nvm/versions/node/v20.0.0/bin"))
        val r = runProbe(listOf("/bin/bash", "-s"), "whitelist.sh", mapOf("ST_DIR" to stDirValue), path = "/usr/bin:/bin")
        assertEquals(r.err, 0, r.code)
        assertEquals("mode=true\nentry=127.0.0.1\n", r.out)
    }

    @Test fun whitelistSaysWhenYamlIsMissing() {
        val node = StpanelTest.realNode
        assumeTrue("whitelist tests need node (not found)", node != null)
        val nodeDir = File(root, "realnode").apply { mkdirs() }
        Files.createSymbolicLink(File(nodeDir, "node").toPath(), node!!.toPath())
        val r = runProbe(listOf("/bin/bash", "-s"), "whitelist.sh", mapOf("ST_DIR" to stDirValue), path = "${nodeDir.path}:/usr/bin:/bin")
        assertNotEquals(0, r.code)
        assertTrue(r.err, r.err.contains("yaml"))
        assertEquals("", r.out)
    }
}
