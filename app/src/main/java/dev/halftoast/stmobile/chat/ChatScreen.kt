package dev.halftoast.stmobile.chat

import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StButton

@Composable
fun ChatScreen(holder: WebViewHolder, onOpenControlCenter: () -> Unit, modifier: Modifier = Modifier) {
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
            onRelease = { holder.detach() },
        )
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
                        Surface(
                            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            shape = RoundedCornerShape(50),
                            color = Color(p.box).copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, Color(p.border)),
                        ) {
                            Text(
                                "Reconnecting…",
                                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                color = Color(p.text),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Color(p.accent),
                    trackColor = Color(p.border),
                )
            }
            ChatState.Stalled -> Surface(
                Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = Color(p.box),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Still loading…", Modifier.weight(1f), color = Color(p.text), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = holder::retry) { Text("Retry", color = Color(p.accent)) }
                }
            }
            ChatState.Ready, ChatState.LoginRequired, ChatState.External -> Unit
            is ChatState.Unreachable -> Surface(Modifier.fillMaxSize(), color = Color(p.background)) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("SillyTavern is unreachable", color = Color(p.text), style = MaterialTheme.typography.titleLarge)
                    Text(s.reason, Modifier.padding(vertical = 12.dp), color = Color(p.muted), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StButton("Retry", holder::retry, Modifier.width(140.dp))
                        StButton("Open Control Center", onOpenControlCenter, Modifier.width(180.dp))
                    }
                }
            }
        }
    }
}

/** Consumes every pointer event that lands here, so views underneath (the WebView) never get it. */
private fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
