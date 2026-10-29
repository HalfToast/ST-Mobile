package dev.halftoast.stmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Slim screen header: back arrow, title, then [actions] (small icon buttons), over a 1 dp border line. */
@Composable
fun StTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Color(p.border), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(p.text))
        }
        Text(
            title,
            Modifier.weight(1f),
            color = Color(p.text),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

private val BoxShape = RoundedCornerShape(5.dp)

/** ST's font scale, recovered from the scaled typography (bodyMedium is 14 sp unscaled). */
@Composable
internal fun fontScale(): Float = MaterialTheme.typography.bodyMedium.fontSize.value / 14f

/** Bold-ish heading above a group of controls, like ST's drawer section titles. */
@Composable
fun StSectionTitle(text: String) {
    Text(
        text,
        color = Color(LocalStPalette.current.text),
        fontSize = (13.5f * fontScale()).sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** ST's bordered, tinted container. */
@Composable
fun StBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalStPalette.current
    Column(
        modifier
            .clip(BoxShape)
            .background(Color(p.box))
            .border(1.dp, Color(p.border), BoxShape)
            .padding(horizontal = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(p.text)) { content() }
    }
}

/** One label/value row inside an [StBox]; [last] drops the divider. */
@Composable
fun StKeyValue(key: String, value: String, last: Boolean = false) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(Color(p.border), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, color = Color(p.muted), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            Modifier.weight(1f),
            color = Color(p.text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

enum class StTone { Normal, Danger }

/** ST's `menu_button`: tinted box with a border; Danger uses the red fill. */
@Composable
fun StButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: StTone = StTone.Normal,
    busy: Boolean = false,
) {
    val p = LocalStPalette.current
    val fill = if (tone == StTone.Danger) p.danger else p.box
    val edge = if (tone == StTone.Danger) p.dangerBorder else p.border
    // Inside a dialog's confirm/dismiss row only the first tap counts: the dialog is going away.
    val guard = LocalDialogGuard.current
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(BoxShape)
            .background(Color(fill))
            .border(BorderStroke(1.dp, Color(edge)), BoxShape)
            .clickable(
                interactionSource = null,
                indication = stPressIndication(BoxShape),
                enabled = enabled,
                role = Role.Button,
                onClick = { if (guard == null || guard.take()) onClick() },
            )
            .padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(p.text))
        } else {
            Text(
                text,
                color = Color(p.text),
                fontSize = (12.5f * fontScale()).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lets a dialog close once: the first confirm, dismiss or Back counts and later ones are ignored, so a
 * double tap can't run Stop or Restart twice while the dialog goes away.
 */
class DialogGuard {
    private var used = false

    fun take(): Boolean {
        if (used) return false
        used = true
        return true
    }
}

/** The guard of the dialog whose confirm/dismiss row this is; null elsewhere. */
val LocalDialogGuard = staticCompositionLocalOf<DialogGuard?> { null }

/**
 * Modal card in ST style: [title], [content], then [dismiss] and [confirm] buttons on the right. It
 * fades and scales in. [StButton]s in [confirm] and [dismiss] must close the dialog: after the first
 * of them (or Back) the dialog ignores further taps on them.
 */
@Composable
fun StDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalStPalette.current
    val shape = RoundedCornerShape(12.dp)
    val guard = remember { DialogGuard() }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(AnimMs, easing = FastOutSlowInEasing)) }
    Dialog(onDismissRequest = { if (guard.take()) onDismiss() }) {
        Column(
            Modifier
                .graphicsLayer {
                    val t = shown.value
                    alpha = t
                    scaleX = 0.92f + 0.08f * t
                    scaleY = 0.92f + 0.08f * t
                }
                .fillMaxWidth()
                .clip(shape)
                .background(Color(p.background))
                .border(1.dp, Color(p.border), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color(p.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CompositionLocalProvider(LocalContentColor provides Color(p.text)) { content() }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalDialogGuard provides guard) {
                    dismiss?.invoke()
                    confirm()
                }
            }
        }
    }
}

/** Shape shared by ST-style inputs. */
val StFieldShape = RoundedCornerShape(5.dp)

/** ST-style text field colours: `box` fill, 1 dp `border`, accent when focused, danger on error. */
@Composable
fun stTextFieldColors() = LocalStPalette.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(p.text), unfocusedTextColor = Color(p.text), disabledTextColor = Color(p.muted),
        focusedContainerColor = Color(p.box), unfocusedContainerColor = Color(p.box), disabledContainerColor = Color(p.box),
        errorContainerColor = Color(p.box),
        focusedBorderColor = Color(p.accent), unfocusedBorderColor = Color(p.border), disabledBorderColor = Color(p.border),
        errorBorderColor = Color(p.dangerBorder),
        focusedLabelColor = Color(p.accent), unfocusedLabelColor = Color(p.muted), disabledLabelColor = Color(p.muted),
        cursorColor = Color(p.accent),
    )
}

/** Switch tinted with the accent. */
@Composable
fun stSwitchColors(): SwitchColors = LocalStPalette.current.let { p ->
    SwitchDefaults.colors(
        checkedThumbColor = Color(p.background), checkedTrackColor = Color(p.accent), checkedBorderColor = Color(p.accent),
        uncheckedThumbColor = Color(p.muted), uncheckedTrackColor = Color(p.box), uncheckedBorderColor = Color(p.border),
    )
}

/** Row of ST-style toggle buttons; the selected one gets a `box` fill and accent text. */
@Composable
fun StSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BoxShape)
            .border(1.dp, Color(p.border), BoxShape),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Color(p.box) else Color.Transparent)
                    .clickable(
                        interactionSource = null,
                        indication = stPressIndication(RectangleShape),
                        role = Role.Tab,
                        onClick = { onSelect(i) },
                    )
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = Color(if (on) p.accent else p.muted),
                    fontSize = (12.5f * fontScale()).sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Length of the app's short UI animations (Compose scales it with the system animation setting). */
const val AnimMs = 200

private const val PressAlpha = 0.12f
private const val PressInMs = 90
private const val PressOutMs = 220

/**
 * ST's soft press feedback for its buttons: a faint wash of the text colour inside [shape], fading in
 * on press and out on release (a quick tap still shows it fully). Drawn over the button only.
 */
@Composable
fun stPressIndication(shape: Shape): Indication {
    val color = Color(LocalStPalette.current.text)
    return remember(shape, color) { StPressIndication(shape, color) }
}

private class StPressIndication(private val shape: Shape, private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StPressNode(interactionSource, shape, color)

    override fun equals(other: Any?) = other is StPressIndication && other.shape == shape && other.color == color

    override fun hashCode() = 31 * shape.hashCode() + color.hashCode()
}

private class StPressNode(
    private val source: InteractionSource,
    private val shape: Shape,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private val level = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            var pressIn: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        pressIn = launch { level.animateTo(1f, tween(PressInMs)) { invalidateDraw() } }
                    }
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    else -> return@collect
                }
                if (presses.isEmpty()) {
                    val shown = pressIn
                    launch {
                        shown?.join()
                        if (presses.isEmpty()) level.animateTo(0f, tween(PressOutMs)) { invalidateDraw() }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val t = level.value
        if (t > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = color.alpha * PressAlpha * t))
    }
}
