package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class KeyInstallerTest {
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTY= st-mobile"
    private lateinit var home: File

    @Before fun setUp() {
        home = Files.createTempDirectory("home").toFile()
    }

    @After fun tearDown() {
        home.deleteRecursively()
    }

    /** Runs the install script the way the app does: [KeyInstaller.INSTALL_COMMAND] with the script on stdin. */
    private fun install(script: String) {
        val p = ProcessBuilder("sh", "-c", KeyInstaller.INSTALL_COMMAND).apply {
            environment()["HOME"] = home.path
            redirectErrorStream(true)
        }.start()
        p.outputStream.use { it.write(script.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
    }

    @Test fun commandIsBashReadingStdin() = assertEquals("env bash -s", KeyInstaller.INSTALL_COMMAND)

    @Test fun appendsTheKeyOnce() {
        val keys = File(home, ".ssh/authorized_keys")
        File(home, ".ssh").mkdirs()
        keys.writeText("ssh-ed25519 AAAAother other@host\n")
        install(KeyInstaller.installScript(key))
        install(KeyInstaller.installScript(key))
        assertEquals("ssh-ed25519 AAAAother other@host\n$key\n", keys.readText())
    }

    @Test fun createsTheSshFolder() {
        install(KeyInstaller.installScript(key))
        assertEquals("$key\n", File(home, ".ssh/authorized_keys").readText())
    }

    @Test fun rejectsAnythingButTheAppsKeyLine() {
        listOf(
            "",
            "ssh-rsa AAAA st-mobile",
            "ecdsa-sha2-nistp256 AAAA other",
            "ecdsa-sha2-nistp256 AA'AA st-mobile",
            "ecdsa-sha2-nistp256 AAAA st-mobile\nrm -rf ~",
            "ecdsa-sha2-nistp256 AAAA st-mobile ",
        ).forEach { bad ->
            try {
                KeyInstaller.installScript(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun badKeyIsRejectedBeforeConnecting() = runTest {
        // Nothing listens on this port: connecting would fail with Unreachable instead.
        val port = ServerSocket(0).use { it.localPort }
        val password = "hunter2".toCharArray()
        try {
            KeyInstaller.install("127.0.0.1", port, "nobody", password, "ecdsa-sha2-nistp256 AA'AA st-mobile")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun unreachableHostMapsToUnreachableAndZeroesPassword() = runTest {
        // A port that was just free on localhost: connection refused, fast.
        val port = ServerSocket(0).use { it.localPort }
        val password = "hunter2".toCharArray()
        try {
            KeyInstaller.install("127.0.0.1", port, "nobody", password, "ecdsa-sha2-nistp256 AAAA st-mobile")
            fail("expected Unreachable")
        } catch (_: SshError.Unreachable) {
        }
        assertTrue(password.all { it == '\u0000' })
    }
}
