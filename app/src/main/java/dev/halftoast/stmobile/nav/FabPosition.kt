package dev.halftoast.stmobile.nav

import dev.halftoast.stmobile.Screen

enum class FabEdge { Left, Right }

/** Where the floating button rests: an edge, and its vertical center as a fraction of the height. */
data class FabPosition(val edge: FabEdge, val fraction: Float) {
    companion object {
        val Default = FabPosition(FabEdge.Right, 0.5f)
        const val MIN_FRACTION = 0.1f
        const val MAX_FRACTION = 0.9f
    }
}

/** Snaps a dropped button (its center, in the same units as [width]/[height]) to the nearest edge. */
fun snapFab(centerX: Float, centerY: Float, width: Float, height: Float): FabPosition = FabPosition(
    edge = if (centerX < width / 2) FabEdge.Left else FabEdge.Right,
    fraction = (centerY / height).coerceIn(FabPosition.MIN_FRACTION, FabPosition.MAX_FRACTION),
)

/**
 * The floating button hides while the keyboard is up or on its way up (its animation target), while
 * one of SillyTavern's lists is open as a sheet (the button would cover its rows), and while Chat shows
 * the Unreachable screen (which has its own buttons).
 */
fun navHidden(imeTargetBottomPx: Int, sheetOpen: Boolean, chatUnreachable: Boolean): Boolean =
    imeTargetBottomPx > 0 || sheetOpen || chatUnreachable

/** The fan's buttons, in order from the top: the three screens, then reloading SillyTavern. */
enum class FanItem(val screen: Screen?, val label: String) {
    ControlCenter(Screen.Panel, Screen.Panel.title),
    Stats(Screen.Stats, Screen.Stats.title),
    Logs(Screen.Logs, Screen.Logs.title),
    Refresh(null, "Reload SillyTavern"),
}

/** Top-left corners (px, inside the button's area) of the resting button and of the fan column. */
data class FabLayout(val restX: Float, val restY: Float, val fanX: Float, val fanTop: Float)

/**
 * Where the button rests in an area of [width]×[height] px, kept whole inside it, and where its fan of
 * [fanCount] buttons opens: beside it toward the interior, centred on it, also kept inside.
 */
fun fabLayout(
    position: FabPosition,
    width: Float,
    height: Float,
    buttonPx: Float,
    marginPx: Float,
    spacingPx: Float,
    fanCount: Int,
): FabLayout {
    val maxX = (width - buttonPx).coerceAtLeast(0f)
    val maxY = (height - buttonPx).coerceAtLeast(0f)
    val left = position.edge == FabEdge.Left
    val restX = (if (left) marginPx else width - marginPx - buttonPx).coerceIn(0f, maxX)
    val restY = (position.fraction * height - buttonPx / 2).coerceIn(0f, maxY)
    val fanHeight = buttonPx * fanCount + spacingPx * (fanCount - 1)
    val fanX = if (left) restX + buttonPx + spacingPx else restX - spacingPx - buttonPx
    val fanTop = (restY + buttonPx / 2 - fanHeight / 2).coerceIn(0f, (height - fanHeight).coerceAtLeast(0f))
    return FabLayout(restX, restY, fanX, fanTop)
}
