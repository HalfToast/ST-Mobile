package dev.halftoast.stmobile.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class FabPositionTest {
    @Test fun snapsToNearestEdge() {
        assertEquals(FabPosition(FabEdge.Left, 0.5f), snapFab(100f, 1000f, 1000f, 2000f))
        assertEquals(FabPosition(FabEdge.Right, 0.25f), snapFab(900f, 500f, 1000f, 2000f))
    }

    @Test fun positionIsRelativeAndClamped() {
        assertEquals(0.1f, snapFab(900f, -50f, 1000f, 2000f).fraction)
        assertEquals(0.9f, snapFab(900f, 5000f, 1000f, 2000f).fraction)
    }

    @Test fun defaultIsRightMiddle() = assertEquals(FabPosition(FabEdge.Right, 0.5f), FabPosition.Default)
}

class FabLayoutTest {
    // 1000×2000 area, 100 px button, 10 px margin, 20 px fan spacing, 3 fan buttons (fan 340 px tall).
    private fun layout(p: FabPosition, w: Float = 1000f, h: Float = 2000f) = fabLayout(p, w, h, 100f, 10f, 20f, 3)

    @Test fun restsOnItsEdgeAtItsFraction() {
        val right = layout(FabPosition(FabEdge.Right, 0.5f))
        assertEquals(890f, right.restX); assertEquals(950f, right.restY)
        val left = layout(FabPosition(FabEdge.Left, 0.25f))
        assertEquals(10f, left.restX); assertEquals(450f, left.restY)
    }

    @Test fun fanOpensTowardTheInteriorCenteredOnTheButton() {
        val right = layout(FabPosition(FabEdge.Right, 0.5f))
        assertEquals(770f, right.fanX); assertEquals(830f, right.fanTop)
        val left = layout(FabPosition(FabEdge.Left, 0.5f))
        assertEquals(130f, left.fanX)
    }

    @Test fun staysInsideATinyOrShortArea() {
        val top = layout(FabPosition(FabEdge.Right, 0.1f), h = 400f)
        assertEquals(0f, top.restY); assertEquals(0f, top.fanTop)
        val bottom = layout(FabPosition(FabEdge.Right, 0.9f), h = 400f)
        assertEquals(300f, bottom.restY); assertEquals(60f, bottom.fanTop)
        val tiny = layout(FabPosition(FabEdge.Right, 0.5f), w = 50f, h = 50f)
        assertEquals(0f, tiny.restX); assertEquals(0f, tiny.restY)
    }
}

class NavHiddenTest {
    @Test fun hiddenWhileKeyboardTargetIsUpOrSheetIsOpen() {
        assertEquals(false, navHidden(imeTargetBottomPx = 0, sheetOpen = false))
        assertEquals(true, navHidden(imeTargetBottomPx = 800, sheetOpen = false))
        assertEquals(true, navHidden(imeTargetBottomPx = 0, sheetOpen = true))
        assertEquals(true, navHidden(imeTargetBottomPx = 800, sheetOpen = true))
    }
}
