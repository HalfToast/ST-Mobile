package dev.halftoast.stmobile.chat

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ChatScreen(holder: WebViewHolder, onOpenControlCenter: () -> Unit, modifier: Modifier = Modifier) {
    val state by holder.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> FrameLayout(ctx).also { holder.attach(it) } },
            onRelease = { holder.detach() },
        )
        when (val s = state) {
            ChatState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            ChatState.Ready, ChatState.LoginRequired -> Unit
            is ChatState.Unreachable -> Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("SillyTavern is unreachable", style = MaterialTheme.typography.titleLarge)
                    Text(s.reason, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = holder::reload) { Text("Retry") }
                        OutlinedButton(onClick = onOpenControlCenter) { Text("Open Control Center") }
                    }
                }
            }
        }
    }
}
