package dev.halftoast.stmobile.chat

import android.widget.FrameLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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
 * The chat WebView with its state overlays: a "Reconnecting…" pill (over the last snapshot, which
 * swallows taps) while Loading, a "Still loading… Retry" pill while Stalled, and [unreachable] over
 * everything while Unreachable. The overlays are small layers over the WebView; nothing here resizes
 * or re-composites it.
 */
@Composable
fun ChatScreen(
    holder: WebViewHolder,
    unreachable: @Composable (ChatState.Unreachable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    val context = LocalContext.current
    // Decoded off the main thread; re-read (and dropped at once) whenever the snapshot is cleared.
    val cleared by Snapshot.cleared.collectAsState()
    val snapshot by produceState<ImageBitmap?>(null, cleared) {
        value = null
        value = Snapshot.load(context.applicationContext)?.asImageBitmap()
    }
    val p = LocalStPalette.current
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> FrameLayout(ctx).also { holder.attach(it) } },
            onRelease = { holder.detach(it) },
        )
        val pillPlace = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
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
                StatusPill("Reconnecting…", pillPlace)
            }
            ChatState.Stalled -> StatusPill("Still loading…", pillPlace) {
                Text(
                    "Retry",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = holder::retry)
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
