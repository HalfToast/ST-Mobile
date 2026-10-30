package dev.halftoast.stmobile.nav

import androidx.activity.BackEventCompat
import dev.halftoast.stmobile.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackMenuTest {
    @Test fun opensOnTheSideTheSwipeCameFrom() {
        assertEquals(MenuEdge.Left, menuEdge(BackEventCompat.EDGE_LEFT))
        assertEquals(MenuEdge.Right, menuEdge(BackEventCompat.EDGE_RIGHT))
    }

    @Test fun aBackWithoutASwipeOpensOnTheRight() {
        assertEquals(MenuEdge.Right, menuEdge(null))
        assertEquals(MenuEdge.Right, menuEdge(BackEventCompat.EDGE_NONE))
    }

    @Test fun itemsTopToBottom() {
        assertEquals(
            listOf(MenuItem.ControlCenter, MenuItem.Stats, MenuItem.Logs, MenuItem.Reload, MenuItem.Quit),
            MenuItem.entries,
        )
        assertEquals(Screen.Panel, MenuItem.ControlCenter.screen)
        assertEquals(null, MenuItem.Reload.screen)
        assertEquals(null, MenuItem.Quit.screen)
    }

    @Test fun everyItemIsInAtTheEndAndOutAtTheStart() {
        val n = MenuItem.entries.size
        for (i in 0 until n) {
            assertEquals(0f, menuItemProgress(0f, i, n))
            assertEquals(1f, menuItemProgress(1f, i, n))
        }
    }

    @Test fun theBottomItemLeadsTheTopOne() {
        val n = MenuItem.entries.size
        val bottom = menuItemProgress(0.3f, n - 1, n)
        val top = menuItemProgress(0.3f, 0, n)
        assertTrue("bottom $bottom, top $top", bottom > top)
        for (i in 0 until n) assertTrue(menuItemProgress(0.5f, i, n) in 0f..1f)
    }
}

class MenuBottomGapTest {
    @Test fun endsJustAboveSillyTavernsMessageBox() = assertEquals(150f + 12f, menuBottomGapDp(150f))

    @Test fun withoutAMessageBoxItKeepsTheDefault() {
        assertEquals(72f, menuBottomGapDp(null))
        assertEquals(72f, menuBottomGapDp(0f))
    }

    @Test fun neverSitsOnTheGestureBarNorClimbsTooHigh() {
        assertEquals(72f, menuBottomGapDp(20f))
        assertEquals(280f, menuBottomGapDp(600f))
    }
}
