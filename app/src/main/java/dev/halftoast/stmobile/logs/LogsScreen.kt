package dev.halftoast.stmobile.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.panel.stripAnsi
import dev.halftoast.stmobile.ssh.RemoteShell
import dev.halftoast.stmobile.ssh.SshError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop

private const val MAX_LINES = 1000

private enum class LogKind(val label: String, val command: String) {
    Server("Server", Probes.TAIL_SERVER_LOG),
    Access("Access", Probes.TAIL_ACCESS_LOG),
}

/** Appends [line], keeping only the newest [max] lines. */
internal fun appendCapped(lines: List<String>, line: String, max: Int = MAX_LINES): List<String> =
    if (lines.size >= max) lines.subList(lines.size - max + 1, lines.size) + line else lines + line

/**
 * Live `tail -F`. The stream is collected inside [LaunchedEffect] (keyed on the Server/Access switch),
 * wrapped in repeatOnLifecycle(STARTED): leaving the tab, switching, or backgrounding the app cancels
 * the collector, which makes the shell TERM the remote tail.
 */
@Composable
fun LogsScreen(shell: RemoteShell, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(LogKind.Server) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyChanged by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(kind, attempt) {
        lines = emptyList()
        error = null
        hostKeyChanged = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                shell.stream(kind.command).collect { raw ->
                    val line = stripAnsi(raw).trimEnd('\r')
                    lines = appendCapped(lines, line)
                }
                error = "The log stream ended."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostKeyChanged = e is SshError.HostKeyChanged
                error = when (e) {
                    is SshError.HostKeyChanged -> "The tablet's SSH host key changed. If you expected this, trust the new key in Settings."
                    is SshError.Unreachable, is SshError.Timeout ->
                        "${e.message}. Check that Tailscale is connected on this phone and the tablet is awake."
                    else -> e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            LogKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { kind = k },
                    shape = SegmentedButtonDefaults.itemShape(i, LogKind.entries.size),
                ) { Text(k.label) }
            }
        }
        error?.let { msg ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hostKeyChanged) {
                            Button(onClick = onOpenSettings) { Text("Open Settings") }
                            TextButton(onClick = { attempt++ }) { Text("Retry") }
                        } else {
                            Button(onClick = { attempt++ }) { Text("Retry") }
                        }
                    }
                }
            }
        }
        key(kind) {
            LogList(lines, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun LogList(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    // Follow the tail while the last item is visible; once the user scrolls up, stop following.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var follow by remember { mutableStateOf(true) }
    // Only a user drag changes the mode: re-evaluate when a scroll gesture ends. Appended lines never do,
    // otherwise a burst of new lines (atBottom briefly false) would switch following off.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) follow = atBottom }
    }
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    if (lines.isEmpty()) {
        Text("Waiting for log lines…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        return
    }
    LazyColumn(modifier = modifier.horizontalScroll(hScroll), state = listState) {
        items(lines.size) { i ->
            Text(
                lines[i],
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                softWrap = false,
            )
        }
    }
}
