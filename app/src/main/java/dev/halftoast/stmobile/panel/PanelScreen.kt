package dev.halftoast.stmobile.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StBox
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StDialog
import dev.halftoast.stmobile.ui.StKeyValue
import dev.halftoast.stmobile.ui.StMono
import dev.halftoast.stmobile.ui.StSectionTitle
import dev.halftoast.stmobile.ui.StTone

/**
 * Control Center (mockup A, ST style): status, update banner, stats box, Server / Maintenance / Whitelist
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

    // Composed only while the Panel tab is shown; ON_STOP covers the app going to the background.
    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        onStopOrDispose { vm.setVisible(false) }
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
            // Persistent instead of a snackbar: an overlay at the bottom sat on top of the action buttons
            // and swallowed taps meant for them.
            ui.lastAction?.let { LastActionLine(it) }
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
                StButton(
                    "Manage whitelist",
                    onClick = { showWhitelist = true; vm.loadWhitelist() },
                    modifier = Modifier.weight(1f),
                )
            }
            ui.dashboard?.let { RecentLogCard(it.recentLogs, onOpenLogs) }
        }
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
    val p = LocalStPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SillyTavern", Modifier.weight(1f), color = Color(p.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (d?.online) {
                true -> StatusChip("Online", Color(p.online), dot = true)
                false -> StatusChip("Offline", Color(p.muted), dot = false)
                null -> StatusChip("Unknown", Color(p.muted), dot = false)
            }
        }
        if (d != null) {
            Text(
                listOfNotNull(d.pid?.let { "PID $it" }, "${d.branch} @ ${d.commit}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Color(p.muted),
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color, dot: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorCard(message: String, hostKeyChanged: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val p = LocalStPalette.current
    StBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hostKeyChanged) "Host key changed" else "Can't read the server",
                color = Color(p.text),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hostKeyChanged) {
                    StButton("Open Settings", onOpenSettings, Modifier.weight(1f))
                    StButton("Retry", onRetry, Modifier.weight(1f))
                } else {
                    StButton("Retry", onRetry, Modifier.weight(1f))
                }
            }
            if (hostKeyChanged) {
                Text(
                    "In Settings, use \"Trust new host key\" only if you know why the key changed (for example, Termux was reinstalled).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(p.muted),
                )
            }
        }
    }
}

@Composable
private fun RestartCard(enabled: Boolean, onRestart: () -> Unit, onLater: () -> Unit) {
    StBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Restart SillyTavern to apply the changes.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StButton("Later", onLater, Modifier.weight(1f))
                StButton("Restart now", onRestart, Modifier.weight(1f), enabled = enabled)
            }
        }
    }
}

@Composable
private fun UpdateBanner(behind: Int, ui: PanelUi, onUpdate: () -> Unit) {
    StBox(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Update available: $behind commit${if (behind == 1) "" else "s"} behind",
                Modifier.weight(1f),
                color = Color(LocalStPalette.current.accent),
                style = MaterialTheme.typography.bodyMedium,
            )
            StButton("Update", onUpdate, enabled = ui.actionsEnabled, busy = ui.isRunning(PanelAction.Update))
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
    StBox(Modifier.fillMaxWidth()) {
        StKeyValue("Uptime", if (d.online) d.uptime ?: "—" else "—")
        StKeyValue("Health", health)
        StKeyValue("CPU / RAM", cpuRam)
        StKeyValue("Server battery", battery)
        StKeyValue("Disk free", disk)
        StKeyValue("Cache", d.cacheSize ?: "—")
        if (d.stDirMissing || d.userDirMissing) {
            Text(
                if (d.stDirMissing) stDirMissingMessage(d.stDir) else userDirMissingMessage(d.userDir),
                Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            StKeyValue(
                "Inventory",
                "${d.characters} characters · ${d.chats} chats · ${d.worlds} worlds · ${d.personas} personas · ${d.backups} backups",
            )
        }
        StKeyValue("Last client", d.lastClient ?: "—", last = true)
    }
}

@Composable
private fun ActionGroup(title: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StSectionTitle(title)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    action: PanelAction,
    ui: PanelUi,
    enabled: Boolean = true,
    onClick: (PanelAction) -> Unit,
) {
    StButton(
        action.label,
        onClick = { onClick(action) },
        modifier = Modifier.weight(1f),
        enabled = enabled && ui.actionsEnabled,
        tone = if (action == PanelAction.Stop) StTone.Danger else StTone.Normal,
        busy = ui.isRunning(action),
    )
}

@Composable
private fun LastActionLine(r: ActionResult) {
    Text(
        "Last action: ${r.message}",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = if (r is ActionResult.Failed) MaterialTheme.colorScheme.error else Color(LocalStPalette.current.muted),
    )
}

@Composable
private fun RecentLogCard(lines: List<String>, onOpenLogs: () -> Unit) {
    val p = LocalStPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StSectionTitle("Recent log") }
            Text(
                "Open Logs",
                Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpenLogs).padding(horizontal = 8.dp, vertical = 6.dp),
                color = Color(p.accent),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        StBox(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (lines.isEmpty()) {
                    Text("No log lines", style = MaterialTheme.typography.bodySmall, color = Color(p.muted))
                }
                lines.forEach {
                    Text(
                        it,
                        fontFamily = StMono,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
            "Runs git pull on the server" + (d?.let { " (${it.branch}, ${it.behind} behind)" } ?: "") +
                ". The chat reloads afterwards; restart the server to finish.",
            "Update",
        )
        else -> Triple("${action.label}?", "", action.label)
    }
    StDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            StButton(button, onConfirm, tone = if (action == PanelAction.Stop) StTone.Danger else StTone.Normal)
        },
        dismiss = { StButton("Cancel", onDismiss) },
    ) { Text(text, style = MaterialTheme.typography.bodyMedium) }
}
