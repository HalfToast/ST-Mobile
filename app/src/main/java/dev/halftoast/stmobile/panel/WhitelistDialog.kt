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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Whitelist mode switch, the allowed entries, and an add field validated live with [isValidIpOrCidr].
 * stpanel has no "remove", so entries are read-only here.
 */
@Composable
fun WhitelistDialog(
    state: WhitelistState?,
    busy: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAdd: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val entry = input.trim()
    val valid = isValidIpOrCidr(entry)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Whitelist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.enabled, onCheckedChange = { onToggle() }, enabled = enabled)
                }
                HorizontalDivider()
                Text("Allowed addresses (${state.entries.size})", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    if (state.entries.isEmpty()) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.entries.forEach { Text(it, fontFamily = FontFamily.Monospace) }
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
                Button(
                    onClick = { onAdd(entry); input = "" },
                    enabled = valid && enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
