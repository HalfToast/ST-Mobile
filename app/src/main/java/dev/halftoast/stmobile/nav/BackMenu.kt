package dev.halftoast.stmobile.nav

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.halftoast.stmobile.Screen
import dev.halftoast.stmobile.ui.AnimMs
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.stPressIndication
import kotlinx.coroutines.delay

/** The screen side the back menu opens on. */
enum class MenuEdge { Left, Right }

/** The side a back gesture came from ([BackEventCompat.swipeEdge]); a back button press has none: right. */
fun menuEdge(swipeEdge: Int?): MenuEdge =
    if (swipeEdge == BackEventCompat.EDGE_LEFT) MenuEdge.Left else MenuEdge.Right

/** The back menu's buttons, from the top: the three screens, reloading SillyTavern, leaving the app. */
enum class MenuItem(val screen: Screen?, val label: String) {
    ControlCenter(Screen.Panel, Screen.Panel.title),
    Stats(Screen.Stats, Screen.Stats.title),
    Logs(Screen.Logs, Screen.Logs.title),
    Reload(null, "Reload"),
    Quit(null, "Quit"),
}

/** What a tap on Quit does: the first one asks ([Ask]), a second one while asking quits. */
enum class QuitTap { Ask, Quit }

fun quitTap(asking: Boolean): QuitTap = if (asking) QuitTap.Quit else QuitTap.Ask

/** Quit's label: the question while waiting for the second tap. */
fun quitLabel(asking: Boolean): String = if (asking) "Are you sure?" else MenuItem.Quit.label

/** How long Quit keeps asking before it goes back to plain Quit. */
const val QUIT_ASK_MS = 3_000L

/** Where the menu opens: the [edge], and the room (dp) left under its column ([menuBottomGapDp]). */
data class MenuSpot(val edge: MenuEdge, val bottomGapDp: Float)

private const val DefaultBottomGapDp = 72f
private const val MaxBottomGapDp = 280f
private const val AboveFormDp = 12f

/**
 * The room (dp, above the bottom system bar) under the menu's column, so it ends just above
 * SillyTavern's message box, which is [sendFormGap] (CSS px, which are dp) tall from the page's bottom;
 * null when there is none to measure. Never on the gesture bar, never so high the column is out of reach.
 */
fun menuBottomGapDp(sendFormGap: Float?): Float =
    if (sendFormGap == null || sendFormGap <= 0f) DefaultBottomGapDp
    else (sendFormGap + AboveFormDp).coerceIn(DefaultBottomGapDp, MaxBottomGapDp)

/** Share of the whole animation by which each button starts after the one below it. */
private const val Stagger = 0.08f

/**
 * Item [index] of [count]'s own 0..1 progress while the whole menu's runs [p] 0..1: the bottom button
 * (nearest the thumb) moves first and each one above follows a little later; all are in at 1.
 */
fun menuItemProgress(p: Float, index: Int, count: Int): Float {
    val start = (count - 1 - index) * Stagger
    val span = 1f - (count - 1) * Stagger
    return ((p - start) / span).coerceIn(0f, 1f)
}

private val ButtonSize = 40.dp
private val IconSize = 20.dp
private val EdgeMargin = 14.dp
private val Spacing = 10.dp
private const val ScrimAlpha = 0.45f
/** Quit's change to "Are you sure?" and back. */
private const val AskMs = 320
/** How far (in button widths) a button starts beyond the screen edge. */
private const val SlideIn = 1.5f

/**
 * What back opens over the chat when SillyTavern has nothing open (mockup `back-menu-lower-2.html`): a
 * dimmed chat and a column of round buttons with labels on [spot]'s edge, low down (just above the
 * message box), sliding in from that edge.
 * Back or a tap outside closes it ([onDismiss]); a button closes it and then runs ([onOpen] for the
 * screens, [onReload]). Quit takes two taps: the first turns it into "Are you sure?" with a check
 * drawing in where the exit arrow was (for [QUIT_ASK_MS]), the second closes the menu and runs [onQuit].
 * [debugLongPress] (debug builds only) runs on a long press on Reload.
 *
 * Call it inside the Chat box, after the chat, only while open (it composes nothing otherwise).
 */
@Composable
fun BackMenu(
    spot: MenuSpot,
    onDismiss: () -> Unit,
    onOpen: (Screen) -> Unit,
    onReload: () -> Unit,
    onQuit: () -> Unit,
    debugLongPress: (() -> Unit)?,
) {
    val palette = LocalStPalette.current
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) { progress.animateTo(1f, tween((AnimMs * 1.5f).toInt(), easing = LinearEasing)) }
    // Slides out, then reports: plain closes dismiss; a button's action runs once it's gone.
    LaunchedEffect(closing) {
        val then = closing ?: return@LaunchedEffect
        progress.animateTo(0f, tween(AnimMs, easing = LinearEasing))
        currentDismiss()
        then()
    }
    fun close(then: () -> Unit = {}) {
        if (closing == null) closing = then
    }

    BackHandler { close() }

    // Quit's question: 0 = plain Quit, 1 = "Are you sure?" with the check drawn.
    var asking by remember { mutableStateOf(false) }
    val ask = remember { Animatable(0f) }
    LaunchedEffect(asking) {
        ask.animateTo(if (asking) 1f else 0f, tween(AskMs, easing = FastOutSlowInEasing))
        if (asking) {
            delay(QUIT_ASK_MS)
            asking = false
        }
    }

    val left = spot.edge == MenuEdge.Left
    val count = MenuItem.entries.size
    Box(
        Modifier
            .fillMaxSize()
            // Dims the chat; drawn, not layered, so nothing goes offscreen over the WebView.
            .drawBehind { drawRect(Color.Black.copy(alpha = ScrimAlpha * progress.value)) }
            // Closes the menu and keeps that tap away from the page underneath.
            .pointerInput(Unit) { detectTapGestures { close() } },
    ) {
        Column(
            Modifier
                .align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = EdgeMargin, end = EdgeMargin, bottom = spot.bottomGapDp.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing),
            horizontalAlignment = if (left) Alignment.Start else Alignment.End,
        ) {
            MenuItem.entries.forEachIndexed { i, item ->
                val quit = item == MenuItem.Quit
                // The whole row takes the tap; the press shows on its round button.
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .graphicsLayer {
                            val t = FastOutSlowInEasing.transform(menuItemProgress(progress.value, i, count))
                            val away = (1f - t) * (size.width + SlideIn * ButtonSize.toPx())
                            translationX = if (left) -away else away
                            alpha = t
                        }
                        .combinedClickable(
                            interactionSource = press,
                            indication = null,
                            onLongClick = if (item == MenuItem.Reload && debugLongPress != null) {
                                { close(debugLongPress) }
                            } else null,
                        ) {
                            when {
                                !quit -> close {
                                    if (item == MenuItem.Reload) onReload() else item.screen?.let(onOpen)
                                }
                                quitTap(asking) == QuitTap.Ask -> asking = true
                                else -> close(onQuit)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val label = @Composable {
                        val pill = Modifier
                            .background(Color(palette.box), RoundedCornerShape(6.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                        if (!quit) {
                            Text(item.label, color = Color(palette.text), fontSize = 13.sp, modifier = pill)
                        } else {
                            // The pill grows toward the screen's middle; the words cross-fade with a small rise.
                            AnimatedContent(
                                targetState = asking,
                                modifier = pill,
                                contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
                                transitionSpec = {
                                    (fadeIn(tween(AskMs)) + slideInVertically(tween(AskMs)) { it / 3 }) togetherWith
                                        (fadeOut(tween(AskMs / 2)) + slideOutVertically(tween(AskMs)) { -it / 3 })
                                },
                                label = "quit label",
                            ) { a -> Text(quitLabel(a), color = Color(palette.text), fontSize = 13.sp) }
                        }
                    }
                    if (!left) label()
                    Box(
                        Modifier
                            .size(ButtonSize)
                            // The system's edge back-gesture zone otherwise takes taps on the button's outer half.
                            .systemGestureExclusion()
                            .background(Color(if (quit) palette.danger else palette.box), CircleShape)
                            .border(1.dp, Color(if (quit) palette.dangerBorder else palette.border), CircleShape)
                            .indication(press, stPressIndication(CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!quit) {
                            Icon(item.icon, contentDescription = null, Modifier.size(IconSize), tint = Color(palette.text))
                        } else {
                            QuitIcon(ask = { ask.value }, tint = Color(palette.text))
                        }
                    }
                    if (left) label()
                }
            }
        }
    }
}

/**
 * Quit's icon while [ask] runs 0 to 1: the exit arrow shrinks and fades as it turns away, then a
 * check is drawn in, stroke by stroke, in the same line style. Read in the draw phase only.
 */
@Composable
private fun QuitIcon(ask: () -> Float, tint: Color) {
    Box(Modifier.size(IconSize), contentAlignment = Alignment.Center) {
        Icon(
            NavIcons.Quit,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(IconSize).graphicsLayer {
                val t = (ask() / 0.6f).coerceIn(0f, 1f)
                alpha = 1f - t
                scaleX = 1f - 0.4f * t
                scaleY = 1f - 0.4f * t
                rotationZ = -30f * t
            },
        )
        val check = remember { Path() }
        val measure = remember { PathMeasure() }
        val drawn = remember { Path() }
        Canvas(Modifier.size(IconSize)) {
            val t = ((ask() - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (t <= 0f) return@Canvas
            val u = size.width / 24f
            check.reset()
            check.moveTo(5f * u, 12.5f * u)
            check.lineTo(10f * u, 17.5f * u)
            check.lineTo(19f * u, 7f * u)
            measure.setPath(check, false)
            drawn.reset()
            measure.getSegment(0f, measure.length * FastOutSlowInEasing.transform(t), drawn, true)
            drawPath(drawn, tint, style = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The mockup's line icons (24-unit SVG paths, round 1.8 strokes), tinted by [Icon]. */
object NavIcons {
    val Chat = lineIcon("Chat", "M4 5h16v11H9l-5 4z")
    val Panel = lineIcon("Panel", "M4 17a8 8 0 1 1 16 0", "M12 17l4-5")
    val Stats = lineIcon("Stats", "M5 20V11M12 20V5M19 20v-6")
    val Logs = lineIcon("Logs", "M5 7l4 4-4 4M11 17h8")
    /** A circular arrow: most of a circle, its arrowhead at the top right. */
    val Refresh = lineIcon("Refresh", "M19 12a7 7 0 1 1-2.05-4.95", "M17.5 3.5v3.6h-3.6")
    /** An arrow leaving through a door. */
    val Quit = lineIcon("Quit", "M10 4H5v16h5", "M14 8l4 4-4 4M18 12H9")

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

val MenuItem.icon: ImageVector
    get() = when (this) {
        MenuItem.Reload -> NavIcons.Refresh
        MenuItem.Quit -> NavIcons.Quit
        else -> screen!!.icon
    }

val Screen.icon: ImageVector
    get() = when (this) {
        Screen.Chat -> NavIcons.Chat
        Screen.Panel -> NavIcons.Panel
        Screen.Stats -> NavIcons.Stats
        Screen.Logs -> NavIcons.Logs
    }
