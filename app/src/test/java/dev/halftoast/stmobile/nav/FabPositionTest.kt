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
