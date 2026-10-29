package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

class KeyInstallerTest {
    @Test fun scriptReadsKeyFromStdinAndIsIdempotent() {
        val s = KeyInstaller.INSTALL_SCRIPT
        assertTrue(s.contains("read -r KEY"))
        assertTrue(s.contains("grep -qxF \"\$KEY\""))
        assertTrue(s.contains("chmod 600"))
        assertFalse(s.contains("ecdsa-sha2"))
    }

    @Test fun scriptIsVerbatim() = assertEquals(
        "read -r KEY; mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "chmod 600 ~/.ssh/authorized_keys && (grep -qxF \"\$KEY\" ~/.ssh/authorized_keys || " +
            "printf '%s\\n' \"\$KEY\" >> ~/.ssh/authorized_keys)",
        KeyInstaller.INSTALL_SCRIPT,
    )

    @Test fun commandRunsScriptUnderBashWithSafeQuoting() {
        assertEquals("'a'\\''b'", KeyInstaller.shellQuote("a'b"))
        assertEquals("bash -c " + KeyInstaller.shellQuote(KeyInstaller.INSTALL_SCRIPT), KeyInstaller.INSTALL_COMMAND)
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
