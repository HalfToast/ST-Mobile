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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StMono
import dev.halftoast.stmobile.ui.StSegmented
import dev.halftoast.stmobile.ui.fontScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.halftoast.stmobile.panel.LogKind
import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.panel.stripAnsi
import dev.halftoast.stmobile.ssh.RemoteShell
import dev.halftoast.stmobile.ssh.SshError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop

private const val MAX_LINES = 1000

private val LogKind.label: String
    get() = when (this) {
        LogKind.Server -> "Server"
        LogKind.Access -> "Access"
    }

private const val ST_DIR = "" // Task 5: settings.stDir

/** One shown line: [id] keeps rows stable while old lines drop off the top; [tone] is worked out once. */
private class LogLine(val id: Long, val text: String, val tone: LogTone)

/** Appends [line], keeping only the newest [max] lines. */
internal fun <T> appendCapped(lines: List<T>, line: T, max: Int = MAX_LINES): List<T> =
    if (lines.size >= max) lines.subList(lines.size - max + 1, lines.size) + line else lines + line

/**
 * Live `tail -F`. The stream is collected inside [LaunchedEffect] (keyed on the Server/Access switch),
 * wrapped in repeatOnLifecycle(STARTED): leaving the tab, switching, or backgrounding the app cancels
 * the collector, which makes the shell TERM the remote tail.
 */
@Composable
fun LogsScreen(shell: RemoteShell, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(LogKind.Server) }
    var lines by remember { mutableStateOf(emptyList<LogLine>()) }
    var nextId by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyChanged by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(kind, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Every (re)start re-emits the last 200 lines, so the buffer and any stale error reset here.
            lines = emptyList()
            error = null
            hostKeyChanged = false
            try {
                val tail = Probes.tail(kind, ST_DIR)
                shell.stream(tail.command, tail.stdin).collect { raw ->
                    val line = stripAnsi(raw).trimEnd('\r')
                    lines = appendCapped(lines, LogLine(nextId++, line, logTone(line)))
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

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StSegmented(LogKind.entries.map { it.label }, LogKind.entries.indexOf(kind)) { kind = LogKind.entries[it] }
        error?.let { msg ->
            val p = LocalStPalette.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(p.danger))
                    .border(1.dp, Color(p.dangerBorder), RoundedCornerShape(5.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(msg, color = Color(p.text))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hostKeyChanged) {
                        StButton("Open Settings", onOpenSettings, Modifier.weight(1f))
                        StButton("Retry", { attempt++ }, Modifier.weight(1f))
                    } else {
                        StButton("Retry", { attempt++ }, Modifier.weight(1f))
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
private fun LogList(lines: List<LogLine>, modifier: Modifier) {
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
    LaunchedEffect(lines.lastOrNull()?.id) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    if (lines.isEmpty()) {
        Text("Waiting for log lines…", color = Color(LocalStPalette.current.muted), modifier = Modifier.padding(8.dp))
        return
    }
    val p = LocalStPalette.current
    val size = (12f * fontScale()).sp
    LazyColumn(modifier = modifier.horizontalScroll(hScroll), state = listState) {
        items(lines, key = { it.id }) { line ->
            val color = when (line.tone) {
                LogTone.Error -> Color(0xFFFF8C8C)
                LogTone.Warn -> Color(0xFFE8C07A)
                LogTone.Normal -> Color(p.text)
            }
            Text(
                line.text,
                color = color,
                fontFamily = StMono,
                fontSize = size,
                lineHeight = size * 1.25f,
                softWrap = false,
            )
        }
    }
}
