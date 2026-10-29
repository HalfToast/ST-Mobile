package dev.halftoast.stmobile.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAliveCopyTest {
    @Test
    fun titleTextAndColor() {
        assertEquals("SillyTavern stays loaded", KeepAliveCopy.TITLE)
        assertEquals("Tap to open your chat", KeepAliveCopy.TEXT)
        assertEquals(0xFFA11B1B.toInt(), KeepAliveCopy.COLOR)
    }
}
