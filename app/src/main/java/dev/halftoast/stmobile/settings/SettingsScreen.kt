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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StSectionTitle
import dev.halftoast.stmobile.ui.StTopBar
import dev.halftoast.stmobile.ui.stSwitchColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    val p = LocalStPalette.current
    Scaffold(
        modifier = modifier,
        containerColor = Color(p.background),
        topBar = {
            Box(Modifier.background(Color(p.background)).windowInsetsPadding(WindowInsets.statusBars)) {
                StTopBar("Settings", onBack = onClose)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StSectionTitle("Connection")
            ConnectionFields(form, enabled = true, onChange = { form = it; message = null })
            StButton("Save", modifier = Modifier.fillMaxWidth(), onClick = {
                val problem = form.validate()
                if (problem != null) {
                    message = problem
                    messageIsError = true
                    return@StButton
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
            })
            message?.let {
                Text(it, color = Color(if (messageIsError) p.dangerBorder else p.accent))
            }

            HorizontalDivider(color = Color(p.border))
            StSectionTitle("Background")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep running in the background", color = Color(p.text))
                    Text(
                        "Shows a notification so Android doesn't unload SillyTavern. Applies when you leave Settings.",
                        color = Color(p.muted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = keepAlive, colors = stSwitchColors(), onCheckedChange = {
                    keepAlive = it
                    settings.keepAliveEnabled = it
                    if (it) activity?.let(KeepAliveService::requestKeepAlivePermissions)
                })
            }
            StButton("Battery settings", { activity?.let { KeepAliveService.openBatterySettings(it) } }, Modifier.fillMaxWidth())

            HorizontalDivider(color = Color(p.border))
            StSectionTitle("Tablet SSH")
            Text(
                pinned?.let { "Pinned host key: $it" } ?: "No host key pinned. The next connection will pin it.",
                color = Color(p.muted),
                style = MaterialTheme.typography.bodySmall,
            )
            StButton(
                "Trust new host key",
                modifier = Modifier.fillMaxWidth(),
                enabled = !checkingKey,
                busy = checkingKey,
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
            )
            keyMessage?.let { Text(it, color = Color(p.text), style = MaterialTheme.typography.bodySmall) }
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
            StButton("Re-run setup", onRerunSetup, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}
