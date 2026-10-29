package dev.halftoast.stmobile.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.halftoast.stmobile.ssh.HostKeyCheck
import dev.halftoast.stmobile.ssh.HostKeyPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.ssh.SshRemoteShell

/**
 * Connection settings (no password), keep-alive, host key and re-run setup.
 * The keep-alive toggle is saved at once but only applied to the service when leaving this screen,
 * so quick on/off flips can't stop the service before it reached the foreground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    shell: SshRemoteShell,
    onClose: () -> Unit,
    onRerunSetup: () -> Unit,
    onServerUrlChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current as? ComponentActivity
    val context = LocalContext.current.applicationContext
    var form by rememberSaveable(stateSaver = ConnectionFormSaver) { mutableStateOf(ConnectionForm.from(settings)) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var keepAlive by remember { mutableStateOf(settings.keepAliveEnabled) }
    var pinned by remember { mutableStateOf(settings.knownHostKey) }
    val scope = rememberCoroutineScope()
    var checkingKey by remember { mutableStateOf(false) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var toConfirm by remember { mutableStateOf<HostKeyCheck?>(null) }

    val keepAliveNow by rememberUpdatedState(keepAlive)
    DisposableEffect(Unit) {
        val atEntry = settings.keepAliveEnabled
        onDispose {
            if (keepAliveNow != atEntry) {
                if (keepAliveNow) KeepAliveService.start(context) else KeepAliveService.stop(context)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            ConnectionFields(form, enabled = true, onChange = { form = it; message = null })
            Button(onClick = {
                val problem = form.validate()
                if (problem != null) {
                    message = problem
                    messageIsError = true
                    return@Button
                }
                val before = Triple(settings.sshHost, settings.sshPort, settings.sshUser)
                form.saveSsh(settings)
                val sshChanged = before != Triple(settings.sshHost, settings.sshPort, settings.sshUser)
                // Undispatched + NonCancellable inside close(): the disconnect runs on IO even if we leave now.
                if (sshChanged) scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.close() }
                if (form.saveServerUrl(settings)) onServerUrlChanged()
                pinned = settings.knownHostKey
                messageIsError = false
                message = if (sshChanged) "Saved. Re-run setup to install the app's key for the new SSH details." else "Saved"
            }) { Text("Save") }
            message?.let {
                Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()
            Text("Background", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep running in the background")
                    Text(
                        "Shows a notification so Android doesn't unload SillyTavern. Applies when you leave Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = keepAlive, onCheckedChange = {
                    keepAlive = it
                    settings.keepAliveEnabled = it
                })
            }
            OutlinedButton(
                onClick = { activity?.let { KeepAliveService.openBatterySettings(it) } },
            ) { Text("Battery settings") }

            HorizontalDivider()
            Text("Tablet SSH", style = MaterialTheme.typography.titleMedium)
            Text(
                pinned?.let { "Pinned host key: $it" } ?: "No host key pinned. The next connection will pin it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !checkingKey,
                    onClick = {
                        checkingKey = true
                        keyMessage = null
                        scope.launch {
                            try {
                                val check = HostKeyPolicy.check(settings.knownHostKey, shell.presentedHostKey())
                                if (check is HostKeyCheck.Matches) {
                                    keyMessage = "The tablet's host key matches the pinned key."
                                } else {
                                    toConfirm = check
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                keyMessage = setupErrorMessage(e, keyStage = false)
                            } finally {
                                checkingKey = false
                            }
                        }
                    },
                ) { Text("Trust new host key") }
                if (checkingKey) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            keyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            toConfirm?.let { check ->
                TrustHostKeyDialog(
                    check = check,
                    onConfirm = {
                        // Pin exactly the fingerprint shown in the dialog.
                        settings.knownHostKey = HostKeyPolicy.pinAfterConfirm(check)
                        pinned = settings.knownHostKey
                        toConfirm = null
                        keyMessage = "Pinned."
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.close() }
                    },
                    onDismiss = { toConfirm = null },
                )
            }
            OutlinedButton(onClick = onRerunSetup) { Text("Re-run setup") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
