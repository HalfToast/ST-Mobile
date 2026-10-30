package dev.halftoast.stmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormTest {
    private fun form(dir: String) = ConnectionForm("http://100.101.102.103:8000", "100.101.102.103", "8022", "u0_a123", dir, "")

    @Test fun acceptsTypicalFolderNames() {
        listOf("default-user", "user_2", "Alice.B", "a").forEach { assertTrue(it, ConnectionForm.isValidStUserDir(it)) }
    }

    @Test fun rejectsAnythingOutsideTheAllowedSet() {
        listOf("", " ", "a b", "a/b", "../x", "a;rm", "\$(id)", "a`b`", "a'b", "a\"b", "ä", "a\nb", "a*")
            .forEach { assertFalse(it, ConnectionForm.isValidStUserDir(it)) }
    }

    @Test fun rejectsDotsOnlyAndLeadingDash() {
        listOf(".", "..", "...", "-rf", "-").forEach { assertFalse(it, ConnectionForm.isValidStUserDir(it)) }
    }

    @Test fun validateReportsStUserDirInline() {
        assertEquals(ConnectionForm.ST_USER_DIR_ERROR, form("a;b").validate())
        assertNull(form("default-user").validate())
    }

    @Test fun urlAloneIsEnough() {
        val f = ConnectionForm("http://192.168.1.20:8000", "", "22", "", "default-user", "")
        assertFalse(f.hasSsh)
        assertNull(f.validate())
    }

    @Test fun sshFieldsAreCheckedOnceOneIsFilled() {
        val url = "http://192.168.1.20:8000"
        assertEquals("Enter the SSH user", ConnectionForm(url, "192.168.1.20", "22", "", "default-user", "").validate())
        assertEquals("Enter the server's SSH host", ConnectionForm(url, "", "22", "u0_a123", "default-user", "").validate())
        assertEquals(
            "Port must be a number from 1 to 65535",
            ConnectionForm(url, "192.168.1.20", "0", "u0_a123", "default-user", "").validate(),
        )
        assertTrue(ConnectionForm(url, "192.168.1.20", "", "", "default-user", "").hasSsh)
        assertTrue(ConnectionForm(url, "", "", "u0_a123", "default-user", "").hasSsh)
    }

    @Test fun blankHostTakesTheUrlsHostOnceAUserIsGiven() {
        val url = "http://192.168.1.20:8000"
        assertEquals("192.168.1.20", ConnectionForm(url, " ", "22", "u0_a123", "default-user", "").withDefaultHost().host)
        assertEquals("", ConnectionForm(url, "", "22", "", "default-user", "").withDefaultHost().host)
        assertEquals("10.0.0.5", ConnectionForm(url, "10.0.0.5", "22", "u0_a123", "default-user", "").withDefaultHost().host)
        assertNull(ConnectionForm(url, "", "8022", "u0_a123", "default-user", "").withDefaultHost().validate())
    }

    @Test fun stDirRule() {
        listOf("", "~/SillyTavern", "/srv/My Apps/SillyTavern").forEach { assertTrue(it, ConnectionForm.isValidStDir(it)) }
        listOf("SillyTavern", "~/a'b", "/a\\b", "/a\$b", "/a\nb", "/a\"b")
            .forEach { assertFalse(it, ConnectionForm.isValidStDir(it)) }
    }

    @Test fun validateReportsStDir() {
        val f = ConnectionForm("http://192.168.1.20:8000", "", "22", "", "default-user", "SillyTavern")
        assertEquals(ConnectionForm.ST_DIR_ERROR, f.validate())
        assertNull(f.copy(stDir = " ~/SillyTavern ").validate())
    }

    @Test fun defaultSshHostIsTheUrlsHost() {
        assertEquals("192.168.1.20", defaultSshHost("http://192.168.1.20:8000"))
        assertEquals("fd00::1", defaultSshHost("http://[fd00::1]:8000"))
        assertEquals("", defaultSshHost("nope"))
    }
}
