package dev.halftoast.stmobile.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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

/**
 * The Chat screen's navigation (mockup `navbar.html`, option B): a small round button resting on a
 * screen edge. Tapping it fans out Control Center, Stats and Logs toward the screen interior; a tap
 * anywhere else closes the fan. Dragging moves it; on release it snaps to the nearer edge and
 * [onMoved] reports the new spot. Hidden while the keyboard is open. [debugLongPress] (debug builds
 * only) runs on a long press.
 *
 * Lay it over the whole screen: it keeps itself inside the system bars, and away from the fan it
 * takes no touches, so the page underneath still gets them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloatingNav(
    position: FabPosition,
    onMoved: (FabPosition) -> Unit,
    onOpen: (Screen) -> Unit,
    debugLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) open = false }
    if (imeVisible) return

    val palette = LocalStPalette.current
    val currentOnMoved by rememberUpdatedState(onMoved)
    val currentLongPress by rememberUpdatedState(debugLongPress)
    Box(modifier.fillMaxSize()) {
        if (open) {
            // Transparent scrim: closes the fan, and keeps that tap away from the page underneath.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { open = false } })
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            val density = LocalDensity.current
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val buttonPx = with(density) { ButtonSize.toPx() }
            val marginPx = with(density) { EdgeMargin.toPx() }
            val spacingPx = with(density) { FanSpacing.toPx() }
            // Top-left bounds that keep the whole button on screen.
            val maxX = (width - buttonPx).coerceAtLeast(0f)
            val maxY = (height - buttonPx).coerceAtLeast(0f)
            val restX = (if (position.edge == FabEdge.Left) marginPx else width - marginPx - buttonPx).coerceIn(0f, maxX)
            val restY = (position.fraction * height - buttonPx / 2).coerceIn(0f, maxY)
            var drag by remember { mutableStateOf(Offset.Zero) }
            var dragging by remember { mutableStateOf(false) }

            if (open) {
                val fanHeight = buttonPx * FanScreens.size + spacingPx * (FanScreens.size - 1)
                val fanX = if (position.edge == FabEdge.Left) restX + buttonPx + spacingPx else restX - spacingPx - buttonPx
                val fanY = (restY + buttonPx / 2 - fanHeight / 2).coerceIn(0f, (height - fanHeight).coerceAtLeast(0f))
                Column(
                    Modifier.offset { IntOffset(fanX.roundToInt(), fanY.roundToInt()) },
                    verticalArrangement = Arrangement.spacedBy(FanSpacing),
                ) {
                    FanScreens.forEach { screen ->
                        RoundButton(
                            fill = Color(palette.box).copy(alpha = 0.92f),
                            border = Color(palette.border),
                            modifier = Modifier.clickable {
                                open = false
                                onOpen(screen)
                            },
                        ) {
                            Icon(screen.icon, contentDescription = screen.title, Modifier.size(IconSize), tint = Color(palette.text))
                        }
                    }
                }
            }

            RoundButton(
                fill = Color(palette.box).copy(alpha = 0.85f),
                border = Color(palette.border),
                modifier = Modifier
                    .offset { IntOffset((restX + drag.x).roundToInt(), (restY + drag.y).roundToInt()) }
                    .alpha(if (open || dragging) 1f else IdleAlpha)
                    .semantics {
                        contentDescription = "Navigation"
                        onClick { open = !open; true }
                    }
                    .pointerInput(position, width, height) {
                        detectDragGestures(
                            onDragStart = { dragging = true; open = false },
                            onDragEnd = {
                                val moved = snapFab(restX + drag.x + buttonPx / 2, restY + drag.y + buttonPx / 2, width, height)
                                dragging = false
                                drag = Offset.Zero
                                currentOnMoved(moved)
                            },
                            onDragCancel = { dragging = false; drag = Offset.Zero },
                        ) { change, amount ->
                            change.consume()
                            drag = Offset(
                                (restX + drag.x + amount.x).coerceIn(0f, maxX) - restX,
                                (restY + drag.y + amount.y).coerceIn(0f, maxY) - restY,
                            )
                        }
                    }
                    .pointerInput(debugLongPress != null) {
                        detectTapGestures(
                            onLongPress = if (debugLongPress != null) ({ currentLongPress?.invoke() }) else null,
                            onTap = { open = !open },
                        )
                    },
            ) {
                Icon(NavIcons.Dots, contentDescription = null, Modifier.size(IconSize), tint = Color(palette.accent))
            }
        }
    }
}

@Composable
private fun RoundButton(fill: Color, border: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .background(fill)
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
