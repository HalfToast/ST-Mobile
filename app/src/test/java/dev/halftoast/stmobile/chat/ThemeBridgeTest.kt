package dev.halftoast.stmobile.chat

import dev.halftoast.stmobile.ui.StTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeBridgeTest {
    @Test fun keepsOnlyKnownVars() {
        assertEquals(mapOf("--SmartThemeQuoteColor" to "rgba(1, 2, 3, 1)"),
            ThemeBridge.parseMessage("""{"--SmartThemeQuoteColor":"rgba(1, 2, 3, 1)","evil":"x"}"""))
    }

    @Test fun rejectsJunk() {
        assertNull(ThemeBridge.parseMessage("[1]")); assertNull(ThemeBridge.parseMessage("nope"))
        assertNull(ThemeBridge.parseMessage("{\"--fontScale\":\"" + "1".repeat(5000) + "\"}"))
    }

    @Test fun scriptNamesEveryVarAndSkipsLogin() {
        val s = ThemeBridge.readAndWatchScript()
        StTheme.CSS_VARS.forEach { assertTrue(it, s.contains(it)) }
        assertTrue(s.contains("/login")); assertTrue(s.contains("MutationObserver"))
    }
}
