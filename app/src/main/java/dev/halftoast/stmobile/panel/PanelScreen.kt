package dev.halftoast.stmobile.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect

private val OnlineGreen = Color(0xFF2E7D32)
private val OfflineRed = Color(0xFFC62828)

/**
 * Control Center (mockup A): status, update banner, stats grid, Server / Maintenance / Whitelist
 * actions and the recent log. Polls only while composed and the app is started.
 */
@Composable
fun PanelScreen(
    vm: PanelViewModel,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.ui.collectAsState()
    var showWhitelist by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Composed only while the Panel tab is shown; ON_STOP covers the app going to the background.
    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        onStopOrDispose { vm.setVisible(false) }
    }

    LaunchedEffect(ui.lastResult) {
        val r = ui.lastResult ?: return@LaunchedEffect
        // Consumed only after it was on screen, so a result that lands while another tab is shown isn't lost.
        snackbar.showSnackbar(
            when (r) {
                is ActionResult.Ok -> r.message
                is ActionResult.Failed -> r.message
            },
        )
        vm.resultShown()
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusHeader(ui.dashboard)
            ui.error?.let { ErrorCard(it, ui.hostKeyChanged, onRetry = vm::refresh, onOpenSettings = onOpenSettings) }
            if (ui.restartSuggested) {
                RestartCard(enabled = ui.actionsEnabled, onRestart = { vm.request(PanelAction.Restart) }, onLater = vm::dismissRestartSuggestion)
            }
            ui.dashboard?.let { d ->
                if (d.behind > 0) UpdateBanner(d.behind, ui, onUpdate = { vm.request(PanelAction.Update) })
                StatsGrid(d)
            }
            if (ui.dashboard == null && ui.error == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Reading server status…")
                }
            }
            val online = ui.dashboard?.online
            ActionGroup("Server") {
                ActionButton(PanelAction.Start, ui, enabled = online != true) { vm.request(it) }
                ActionButton(PanelAction.Stop, ui, enabled = online != false) { vm.request(it) }
                ActionButton(PanelAction.Restart, ui, enabled = online != false) { vm.request(it) }
            }
            ActionGroup("Maintenance") {
                ActionButton(PanelAction.Update, ui) { vm.request(it) }
                ActionButton(PanelAction.Backup, ui) { vm.request(it) }
                ActionButton(PanelAction.CleanCache, ui) { vm.request(it) }
            }
            ActionGroup("Whitelist") {
                OutlinedButton(
                    onClick = { showWhitelist = true; vm.loadWhitelist() },
                    modifier = Modifier.weight(1f),
                ) { Text("Manage whitelist") }
            }
            ui.dashboard?.let { RecentLogCard(it.recentLogs, onOpenLogs) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    ui.pendingConfirm?.let { action ->
        ConfirmDialog(action, ui.dashboard, onConfirm = vm::confirm, onDismiss = vm::dismiss)
    }
    if (showWhitelist) {
        WhitelistDialog(
            ui = ui,
            onRetry = vm::loadWhitelist,
            onToggle = vm::toggleWhitelist,
            onConfirmToggle = vm::confirmWhitelistToggle,
            onDismissToggle = vm::dismissWhitelistToggle,
            onAdd = vm::addWhitelistEntry,
            onClose = { showWhitelist = false; vm.dismissWhitelistToggle() },
        )
    }
}

@Composable
private fun StatusHeader(d: DashboardState?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SillyTavern", style = MaterialTheme.typography.titleLarge)
            if (d != null) {
                Text(
                    listOfNotNull(d.pid?.let { "PID $it" }, "${d.branch} @ ${d.commit}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (d?.online) {
            true -> Pill("Online", OnlineGreen)
            false -> Pill("Offline", OfflineRed)
            null -> Pill("Unknown", MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorCard(message: String, hostKeyChanged: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hostKeyChanged) "Host key changed" else "Can't read the server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostKeyChanged) {
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                } else {
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
            if (hostKeyChanged) {
                Text(
                    "In Settings, use \"Trust new host key\" only if you know why the key changed (for example, Termux was reinstalled).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun RestartCard(enabled: Boolean, onRestart: () -> Unit, onLater: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Restart SillyTavern to apply the changes.",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onLater) { Text("Later") }
            Button(onClick = onRestart, enabled = enabled) { Text("Restart now") }
        }
    }
}

@Composable
private fun UpdateBanner(behind: Int, ui: PanelUi, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Update available: $behind commit${if (behind == 1) "" else "s"} behind",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onUpdate, enabled = ui.actionsEnabled) {
                if (ui.busy == PanelAction.Update) BusySpinner() else Text("Update")
            }
        }
    }
}

@Composable
private fun StatsGrid(d: DashboardState) {
    val health = when {
        !d.online -> "—"
        d.serving -> "HTTP ${d.httpCode}" + (d.latencyMs?.let { " · $it ms" } ?: "")
        else -> "No response"
    }
    val cpuRam = if (d.online) listOfNotNull(d.cpuPercent?.let { "$it %" }, d.ramMb?.let { "$it MB" }).joinToString(" · ").ifEmpty { "—" } else "—"
    val battery = d.batteryLevel?.let { lvl -> "$lvl %" + (d.batteryStatus?.let { " · $it" } ?: "") } ?: "—"
    val disk = d.diskFree?.let { free -> free + (d.diskUsedPercent?.let { " ($it used)" } ?: "") } ?: "—"
    val tiles = listOf(
        "Uptime" to (if (d.online) d.uptime ?: "—" else "—"),
        "Health" to health,
        "CPU / RAM" to cpuRam,
        "Tablet battery" to battery,
        "Disk free" to disk,
        "Cache" to (d.cacheSize ?: "—"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
            }
        }
        StatTile(
            "Inventory",
            "${d.characters} characters · ${d.chats} chats · ${d.worlds} worlds · ${d.personas} personas · ${d.backups} backups",
            Modifier.fillMaxWidth(),
        )
        StatTile("Last client", d.lastClient ?: "—", Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActionGroup(title: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    action: PanelAction,
    ui: PanelUi,
    enabled: Boolean = true,
    onClick: (PanelAction) -> Unit,
) {
    val destructive = action == PanelAction.Stop
    FilledTonalButton(
        onClick = { onClick(action) },
        enabled = enabled && ui.actionsEnabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = if (destructive) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else ButtonDefaults.filledTonalButtonColors(),
    ) {
        if (ui.busy == action) BusySpinner() else Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BusySpinner() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

@Composable
private fun RecentLogCard(lines: List<String>, onOpenLogs: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent log", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenLogs) { Text("Open Logs") }
            }
            if (lines.isEmpty()) {
                Text("No log lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lines.forEach {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ConfirmDialog(action: PanelAction, d: DashboardState?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, text, button) = when (action) {
        PanelAction.Stop -> Triple("Stop SillyTavern?", "The chat will be unavailable until you start it again.", "Stop")
        PanelAction.CleanCache -> Triple(
            "Clean cache?",
            "Deletes the files in data/_cache" + (d?.cacheSize?.let { " ($it)" } ?: "") + ". SillyTavern rebuilds them as needed.",
            "Clean",
        )
        PanelAction.Update -> Triple(
            "Update SillyTavern?",
            "Runs git pull on the tablet" + (d?.let { " (${it.branch}, ${it.behind} behind)" } ?: "") +
                ". The chat reloads afterwards; restart the server to finish.",
            "Update",
        )
        else -> Triple("${action.label}?", "", action.label)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
