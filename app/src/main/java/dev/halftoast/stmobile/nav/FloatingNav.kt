package dev.halftoast.stmobile.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.Screen
import dev.halftoast.stmobile.ui.LocalStPalette
import kotlin.math.roundToInt

private val ButtonSize = 40.dp
private val IconSize = 20.dp
private val EdgeMargin = 8.dp
private val FanSpacing = 8.dp
private const val IdleAlpha = 0.75f
private val FanScreens = listOf(Screen.Panel, Screen.Stats, Screen.Logs)
/** Where a hidden button is placed: far outside the window, so it neither draws nor takes touches. */
private val HiddenOffset = IntOffset(-100_000, -100_000)

/**
 * The Chat screen's navigation (mockup `navbar.html`, option B): a small round button resting on a
 * screen edge. Tapping it fans out Control Center, Stats and Logs toward the screen interior; a tap
 * anywhere else closes the fan. Dragging moves it; on release it snaps to the nearer edge and
 * [onMoved] reports the new spot. [debugLongPress] (debug builds only) runs on a long press.
 *
 * Call it inside the Chat box, after the chat; [containerSize] is that box's size. The button keeps
 * itself inside the system bars. While [hidden] (keyboard or list sheet open) it is placed off screen
 * and the fan closes at once.
 *
 * Keyboard performance: it is composed with the same structure all the time, and [hidden], the insets
 * and the drag are read only in placement lambdas and at event time, so a keyboard animation never
 * recomposes, re-measures or re-layers anything here. Away from the button (and the fan while open) it
 * takes no touches, so the page underneath still gets them.
 */
@Composable
fun FloatingNav(
    position: FabPosition,
    containerSize: () -> IntSize,
    hidden: () -> Boolean,
    onMoved: (FabPosition) -> Unit,
    onOpen: (Screen) -> Unit,
    debugLongPress: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val palette = LocalStPalette.current
    val bars = WindowInsets.systemBars
    val currentPosition by rememberUpdatedState(position)
    val currentOnMoved by rememberUpdatedState(onMoved)
    val currentLongPress by rememberUpdatedState(debugLongPress)
    val currentHidden by rememberUpdatedState(hidden)

    // The keyboard or a sheet appeared: close the fan at once.
    LaunchedEffect(Unit) {
        snapshotFlow { currentHidden() }.collect { if (it) open = false }
    }

    /** The button's area: the Chat box inside the system bars, in px. */
    fun Density.area(): IntRect {
        val size = containerSize()
        return IntRect(
            bars.getLeft(this, LayoutDirection.Ltr),
            bars.getTop(this),
            size.width - bars.getRight(this, LayoutDirection.Ltr),
            size.height - bars.getBottom(this),
        )
    }

    fun Density.fab(area: IntRect) = fabLayout(
        currentPosition, area.width.toFloat(), area.height.toFloat(),
        ButtonSize.toPx(), EdgeMargin.toPx(), FanSpacing.toPx(), FanScreens.size,
    )

    if (open) {
        // Transparent scrim: closes the fan, and keeps that tap away from the page underneath.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { open = false } })
        FanScreens.forEachIndexed { i, screen ->
            RoundButton(
                fill = Color(palette.box).copy(alpha = 0.92f),
                border = Color(palette.border),
                modifier = Modifier
                    .offset {
                        val r = area()
                        val fab = fab(r)
                        val y = fab.fanTop + i * (ButtonSize.toPx() + FanSpacing.toPx())
                        r.topLeft + IntOffset(fab.fanX.roundToInt(), y.roundToInt())
                    }
                    .clickable {
                        open = false
                        onOpen(screen)
                    },
            ) {
                Icon(screen.icon, contentDescription = screen.title, Modifier.size(IconSize), tint = Color(palette.text))
            }
        }
    }

    // Idle, the button is see-through. Baked into the colours (no alpha layer over the WebView).
    val a = if (open || dragging) 1f else IdleAlpha
    RoundButton(
        fill = Color(palette.box).copy(alpha = 0.85f * a),
        border = Color(palette.border).copy(alpha = a),
        modifier = Modifier
            .offset {
                if (currentHidden()) return@offset HiddenOffset
                val r = area()
                val fab = fab(r)
                r.topLeft + IntOffset((fab.restX + drag.x).roundToInt(), (fab.restY + drag.y).roundToInt())
            }
            .semantics {
                contentDescription = "Navigation"
                onClick { open = !open; true }
            }
            .pointerInput(Unit) {
                var ignore = false
                detectDragGestures(
                    onDragStart = {
                        ignore = currentHidden()
                        if (!ignore) {
                            dragging = true
                            open = false
                        }
                    },
                    onDragEnd = {
                        val r = area()
                        val fab = fab(r)
                        val half = ButtonSize.toPx() / 2
                        val moved = snapFab(fab.restX + drag.x + half, fab.restY + drag.y + half, r.width.toFloat(), r.height.toFloat())
                        val save = !ignore && !currentHidden()
                        dragging = false
                        drag = Offset.Zero
                        if (save) currentOnMoved(moved)
                    },
                    onDragCancel = { dragging = false; drag = Offset.Zero },
                ) { change, amount ->
                    if (ignore || currentHidden()) return@detectDragGestures
                    change.consume()
                    val r = area()
                    val fab = fab(r)
                    val maxX = (r.width - ButtonSize.toPx()).coerceAtLeast(0f)
                    val maxY = (r.height - ButtonSize.toPx()).coerceAtLeast(0f)
                    drag = Offset(
                        (fab.restX + drag.x + amount.x).coerceIn(0f, maxX) - fab.restX,
                        (fab.restY + drag.y + amount.y).coerceIn(0f, maxY) - fab.restY,
                    )
                }
            }
            .pointerInput(debugLongPress != null) {
                detectTapGestures(
                    onLongPress = if (debugLongPress != null) ({ if (!currentHidden()) currentLongPress?.invoke() }) else null,
                    onTap = { if (!currentHidden()) open = !open },
                )
            },
    ) {
        Icon(NavIcons.Dots, contentDescription = null, Modifier.size(IconSize), tint = Color(palette.accent).copy(alpha = a))
    }
}

@Composable
private fun RoundButton(fill: Color, border: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(ButtonSize)
            .background(fill, CircleShape)
            .border(1.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The mockup's line icons (24-unit SVG paths, round 1.8 strokes), tinted by [Icon]. */
object NavIcons {
    val Chat = lineIcon("Chat", "M4 5h16v11H9l-5 4z")
    val Panel = lineIcon("Panel", "M4 17a8 8 0 1 1 16 0", "M12 17l4-5")
    val Stats = lineIcon("Stats", "M5 20V11M12 20V5M19 20v-6")
    val Logs = lineIcon("Logs", "M5 7l4 4-4 4M11 17h8")
    val Dots = lineIcon("Dots", "M5 12h.01M12 12h.01M19 12h.01", strokeWidth = 3.2f)

    private fun lineIcon(name: String, vararg paths: String, strokeWidth: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}

val Screen.icon: ImageVector
    get() = when (this) {
        Screen.Chat -> NavIcons.Chat
        Screen.Panel -> NavIcons.Panel
        Screen.Stats -> NavIcons.Stats
        Screen.Logs -> NavIcons.Logs
    }
