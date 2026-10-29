package dev.halftoast.stmobile.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StDialog
import dev.halftoast.stmobile.ui.StMono

/**
 * Whitelist mode switch, the allowed entries, and an add field validated live with [isValidIpOrCidr].
 * stpanel has no "remove", so entries are read-only here. The switch only asks for a toggle
 * ([onToggle]); the change runs after the confirmation dialog.
 */
@Composable
fun WhitelistDialog(
    ui: PanelUi,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onConfirmToggle: () -> Unit,
    onDismissToggle: () -> Unit,
    onAdd: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val entry = input.trim()
    val valid = isValidIpOrCidr(entry)
    val state = ui.whitelist
    val loadError = ui.whitelistError
    StDialog(
        title = "Whitelist",
        onDismiss = onClose,
        confirm = { StButton("Close", onClose) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loadError != null) {
                Text(loadError, color = MaterialTheme.colorScheme.error)
                StButton("Retry", onRetry)
                return@Column
            }
            if (state == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Reading config.yaml…")
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Whitelist mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.enabled) "On: only the addresses below can connect" else "Off: any address can connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.whitelistLoading || ui.whitelistBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.enabled, onCheckedChange = { onToggle() }, enabled = ui.whitelistToggleEnabled)
            }
            ui.whitelistMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("Allowed addresses (${state.entries.size})", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                if (state.entries.isEmpty()) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.entries.forEach { Text(it, fontFamily = StMono) }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Add IP or CIDR subnet") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                isError = input.isNotBlank() && !valid,
                supportingText = {
                    Text(if (input.isNotBlank() && !valid) "Not a valid IP address or subnet" else "Changes apply after a restart")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            StButton(
                "Add",
                onClick = { onAdd(entry); input = "" },
                enabled = valid && ui.actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    ui.pendingWhitelistToggle?.let { p ->
        StDialog(
            title = if (p.enable) "Turn whitelist mode on?" else "Turn whitelist mode off?",
            onDismiss = onDismissToggle,
            confirm = { StButton(if (p.enable) "Turn on" else "Turn off", onConfirmToggle) },
            dismiss = { StButton("Cancel", onDismissToggle) },
        ) { Text(p.warning + " The change applies after SillyTavern restarts.", style = MaterialTheme.typography.bodyMedium) }
    }
}
