package dev.halftoast.stmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormTest {
    private fun form(dir: String) = ConnectionForm("http://100.101.102.103:8000", "100.101.102.103", "8022", "u0_a123", dir)

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
}
