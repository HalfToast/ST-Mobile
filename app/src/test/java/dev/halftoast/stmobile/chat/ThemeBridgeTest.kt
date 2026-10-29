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

    @Test fun watcherPostsOnlyChangedThemes() {
        val s = ThemeBridge.readAndWatchScript()
        // The last posted (or first returned) JSON is remembered, and an unchanged read is not posted.
        assertTrue(s.contains("window.__stmThemeSent=j"))
        assertTrue(s.contains("if(j===window.__stmThemeSent)return;"))
        assertTrue("throttle kept", s.contains("300-(Date.now()-last)"))
    }

    @Test fun loggedValueIsShortAndOnOneLine() {
        assertEquals("rgba(170, 219, 255, 1)", ThemeBridge.logValue("rgba(170, 219, 255, 1)"))
        assertEquals("redgreen", ThemeBridge.logValue("red\n\r\u0000green\u001b"))
        assertEquals("x".repeat(64), ThemeBridge.logValue("x".repeat(4000)))
        assertEquals("null", ThemeBridge.logValue(null))
    }
}
