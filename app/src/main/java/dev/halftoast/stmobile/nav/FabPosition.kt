package dev.halftoast.stmobile.nav

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
