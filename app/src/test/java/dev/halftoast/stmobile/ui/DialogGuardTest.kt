package dev.halftoast.stmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogGuardTest {
    @Test fun onlyTheFirstCloseCounts() {
        val guard = DialogGuard()
        assertEquals(true, guard.take())   // e.g. Stop
        assertEquals(false, guard.take())  // a second tap on Stop while the dialog goes away
        assertEquals(false, guard.take())  // Cancel or Back after it
    }
}
