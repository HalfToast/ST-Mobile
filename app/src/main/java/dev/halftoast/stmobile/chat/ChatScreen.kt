package dev.halftoast.stmobile.chat

import android.widget.FrameLayout
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.TouchBoundsExpansion
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntSize
import dev.halftoast.stmobile.ui.stPressIndication
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.halftoast.stmobile.ui.LocalStPalette

/**
 * The chat WebView with its state overlays: a "Connecting…" or "Reconnecting…" pill (over the last
 * snapshot, which swallows taps) while Loading ([loadingPillText]), a "Still loading… Retry" pill
 * while Stalled, [unreachable] over everything while Unreachable, and [covered] over a loaded page
 * whose connection was lost. The overlays are small layers over the WebView; nothing here resizes
 * or re-composites it.
 */
@Composable
fun ChatScreen(
    holder: WebViewHolder,
    unreachable: @Composable (ChatState.Unreachable) -> Unit,
    covered: @Composable (UnreachableCase) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    val cover by holder.cover.collectAsState()
    val pageWasLive by holder.pageWasLive.collectAsState()
    val context = LocalContext.current
    // Decoded off the main thread, only while Loading (so it is the latest save, and a full-screen
    // bitmap isn't held while the chat is up); dropped at once whenever the snapshot is cleared.
    val cleared by Snapshot.cleared.collectAsState()
    val loading = state == ChatState.Loading
    val snapshot by produceState<ImageBitmap?>(null, cleared, loading) {
        value = null
        if (loading) value = Snapshot.load(context.applicationContext)?.asImageBitmap()
    }
    val p = LocalStPalette.current
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> FrameLayout(ctx).also { holder.attach(it) } },
            onRelease = { holder.detach(it) },
        )
        val pillPlace = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        // The connection dropped under the loaded page: cover it (it stays loaded underneath).
        cover?.let { case ->
            if (state.coversOnConnectionLoss) {
                covered(case)
                return@Box
            }
        }
        when (val s = state) {
            ChatState.Loading -> {
                snapshot?.let { bitmap ->
                    // The page underneath is loading behind a picture of the old one: swallow every
                    // touch so nothing lands on a control the user can't see.
                    Box(Modifier.fillMaxSize().consumeAllPointerInput()) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                        )
                    }
                }
                StatusPill(loadingPillText(pageWasLive), pillPlace)
            }
            ChatState.Stalled -> StatusPill("Still loading…", pillPlace) {
                val press = remember { MutableInteractionSource() }
                Text(
                    "Retry",
                    Modifier
                        .compactTap(press, holder::retry)
                        .indication(press, stPressIndication(RoundedCornerShape(50)))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    color = Color(p.accent),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            ChatState.Ready, ChatState.LoginRequired, ChatState.External -> Unit
            is ChatState.Unreachable -> unreachable(s)
        }
    }
}

/** ST-style status pill: `box` fill, 1 dp border, a softly pulsing accent dot, [text], then [action]. */
@Composable
private fun StatusPill(text: String, modifier: Modifier, action: (@Composable () -> Unit)? = null) {
    val p = LocalStPalette.current
    val shape = RoundedCornerShape(50)
    val pulse = rememberInfiniteTransition(label = "pulse")
    // Read only in the dot's layer: the pulse redraws that layer, nothing else.
    val dot by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_HALF_MS), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        modifier
            .background(Color(p.box), shape)
            .border(1.dp, Color(p.border), shape)
            .padding(start = 12.dp, end = if (action == null) 14.dp else 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer {
                    alpha = dot
                    scaleX = 0.8f + 0.2f * dot
                    scaleY = 0.8f + 0.2f * dot
                }
                .background(Color(p.accent), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(p.text), style = MaterialTheme.typography.labelLarge)
        action?.let {
            Spacer(Modifier.width(4.dp))
            it()
        }
    }
}

/** Half of the pill dot's ~1.2 s pulse. */
private const val PULSE_HALF_MS = 600

/** Consumes every pointer event that lands here, so views underneath (the WebView) never get it. */
private fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** The smallest tap target, as Material asks for. */
private val MinTouch = 48.dp

/**
 * A tap target that reaches at least [MinTouch] square around a smaller element without growing its
 * layout (the pill keeps its size): the extra area is a touch-bounds expansion. Emits press
 * interactions to [interactions] for the highlight, and is a button for accessibility.
 */
private fun Modifier.compactTap(interactions: MutableInteractionSource, onClick: () -> Unit): Modifier =
    this then CompactTapElement(interactions, onClick)

private data class CompactTapElement(
    val interactions: MutableInteractionSource,
    val onTap: () -> Unit,
) : ModifierNodeElement<CompactTapNode>() {
    override fun create() = CompactTapNode(interactions, onTap)

    override fun update(node: CompactTapNode) {
        node.interactions = interactions
        node.onTap = onTap
    }
}

private class CompactTapNode(
    var interactions: MutableInteractionSource,
    var onTap: () -> Unit,
) : Modifier.Node(), PointerInputModifierNode, SemanticsModifierNode, LayoutAwareModifierNode {
    private var size = IntSize.Zero
    private var press: PressInteraction.Press? = null

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override val touchBoundsExpansion: TouchBoundsExpansion
        get() {
            val min = with(requireDensity()) { MinTouch.roundToPx() }
            val dx = ((min - size.width + 1) / 2).coerceAtLeast(0)
            val dy = ((min - size.height + 1) / 2).coerceAtLeast(0)
            return TouchBoundsExpansion(dx, dy, dx, dy)
        }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        when {
            change.changedToDown() && !change.isConsumed -> {
                change.consume()
                endPress(released = false)
                press = PressInteraction.Press(change.position).also { interactions.tryEmit(it) }
            }
            change.changedToUp() && press != null -> {
                // Lifted after sliding off the button (past its touch slop area): no tap.
                val tapped = !change.isConsumed && isInside(change.position, bounds)
                endPress(released = tapped)
                if (tapped) {
                    change.consume()
                    onTap()
                }
            }
        }
    }

    private fun isInside(pos: Offset, bounds: IntSize): Boolean {
        val e = touchBoundsExpansion // symmetric, so start/end need no layout direction
        return pos.x >= -e.start && pos.x <= bounds.width + e.end && pos.y >= -e.top && pos.y <= bounds.height + e.bottom
    }

    override fun onCancelPointerInput() = endPress(released = false)

    override fun onDetach() = endPress(released = false)

    private fun endPress(released: Boolean) {
        val p = press ?: return
        press = null
        interactions.tryEmit(if (released) PressInteraction.Release(p) else PressInteraction.Cancel(p))
    }

    override val shouldMergeDescendantSemantics get() = true

    override fun SemanticsPropertyReceiver.applySemantics() {
        role = Role.Button
        onClick { onTap(); true }
    }
}
