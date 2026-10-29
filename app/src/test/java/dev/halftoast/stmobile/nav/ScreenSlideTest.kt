package dev.halftoast.stmobile.nav

import dev.halftoast.stmobile.Overlay
import dev.halftoast.stmobile.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSlideTest {
    @Test fun chatHasSettledOnlyWithNothingOverIt() {
        assertEquals(true, chatSettled(Screen.Chat, Overlay.None, covering = null))
        // Back pressed on Logs: Chat is the target, but Logs is still sliding out over it.
        assertEquals(false, chatSettled(Screen.Chat, Overlay.None, covering = Screen.Logs))
        // Opening Stats: it slides in over a Chat that is still there.
        assertEquals(false, chatSettled(Screen.Stats, Overlay.None, covering = Screen.Stats))
        assertEquals(false, chatSettled(Screen.Panel, Overlay.Settings, covering = Screen.Panel))
    }

    @Test fun nothingSlidesWhileTheKeyboardIsUpOrMoving() {
        assertEquals(true, slideAnimates(imeBottomPx = 0, imeTargetBottomPx = 0))
        assertEquals(false, slideAnimates(imeBottomPx = 300, imeTargetBottomPx = 900)) // opening
        assertEquals(false, slideAnimates(imeBottomPx = 300, imeTargetBottomPx = 0))   // closing
        assertEquals(false, slideAnimates(imeBottomPx = 900, imeTargetBottomPx = 900)) // open
    }
}
