package dev.halftoast.stmobile.settings

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StDialog
import dev.halftoast.stmobile.ui.StFieldShape
import dev.halftoast.stmobile.ui.StSectionTitle
import dev.halftoast.stmobile.ui.stTextFieldColors
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.ssh.HostKeyCheck
import dev.halftoast.stmobile.ssh.HostKeyPolicy
import dev.halftoast.stmobile.ssh.KeyInstaller
import dev.halftoast.stmobile.ssh.KeyManager
import dev.halftoast.stmobile.ssh.SshError
import dev.halftoast.stmobile.ssh.SshRemoteShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-facing text for a failed setup. */
fun setupErrorMessage(e: Throwable, keyStage: Boolean): String = when (e) {
    is SshError.AuthFailed ->
        if (keyStage) "The key was installed but the tablet didn't accept it. Try again." else "Password rejected"
    is SshError.Unreachable -> "Can't reach the tablet. Is Tailscale on?"
    is SshError.Timeout -> "The tablet didn't answer in time. Is Tailscale on?"
    is SshError.HostKeyChanged ->
        "The tablet's SSH host key changed (pinned ${e.expected}, now ${e.actual}). " +
            "Only trust the new key if you reinstalled Termux or SSH on the tablet."
    else -> "Setup failed: ${e.message ?: e.javaClass.simpleName}"
}

/**
 * Connects to the tablet once with the password, installs the app's key, pins the host key and
 * checks key login. The password lives only in a non-saveable field and a CharArray that is
 * zeroed after the attempt; the field is cleared as soon as Connect is pressed.
 */
@Composable
fun SetupScreen(
    settings: AppSettings,
    keys: KeyManager,
    shell: SshRemoteShell,
    onComplete: (serverUrlChanged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    /** True only for "Re-run setup" opened from Settings: offers trusting a changed host key there. */
    allowTrust: Boolean = false,
    /** First run: a changed host key is resolved in Settings, which this opens. */
    onOpenSettings: (() -> Unit)? = null,
) {
    val activity = LocalActivity.current as? ComponentActivity
    val scope = rememberCoroutineScope()
    var form by rememberSaveable(stateSaver = ConnectionFormSaver) { mutableStateOf(ConnectionForm.from(settings)) }
    // Deliberately not rememberSaveable: the password must never reach the saved instance state.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyChange by remember { mutableStateOf<HostKeyCheck.Changed?>(null) }
    var confirmTrust by remember { mutableStateOf(false) }
    var keyProblem by remember { mutableStateOf<String?>(null) }

    // Make (or unlock) the app key up front so a Keystore problem shows before the password is typed.
    LaunchedEffect(keys) {
        try {
            val kp = withContext(Dispatchers.IO) { keys.getOrCreate() }
            Log.i(StMobileApp.LOG_TAG, "ssh: app key ready, public ${HostKeyPolicy.fingerprint(kp.public)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(StMobileApp.LOG_TAG, "ssh: app key unavailable: ${e.javaClass.simpleName}")
            keyProblem = "Couldn't create the app's SSH key: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun connect() {
        val problem = form.validate() ?: if (password.isEmpty()) "Enter the tablet password" else null
        if (problem != null) {
            error = problem
            return
        }
        val pw = password.toCharArray()
        password = ""
        error = null
        hostKeyChange = null
        busy = true
        val f = form
        scope.launch {
            var keyStage = false
            var done = false
            // Saved up front (the key-login test reads them), so put back if setup fails or is left.
            val before = SshFields.of(settings)
            try {
                f.saveSsh(settings)
                val line = withContext(Dispatchers.IO) { keys.authorizedKeysLine() }
                val seen = KeyInstaller.install(settings.sshHost, settings.sshPort, settings.sshUser, pw, line, settings.knownHostKey)
                if (settings.knownHostKey.isNullOrEmpty()) settings.knownHostKey = seen
                keyStage = true
                shell.close() // drop any connection made with older settings
                val r = shell.exec("echo ok", timeoutMs = 15_000)
                check(r.exitCode == 0 && r.stdout.trim() == "ok") { "key login test returned ${r.exitCode}" }
                val urlChanged = f.saveServerUrl(settings)
                settings.setupComplete = true
                Log.i(StMobileApp.LOG_TAG, "ssh: setup complete, host key $seen")
                done = true
                busy = false
                activity?.let { KeepAliveService.requestKeepAlivePermissions(it) }
                onComplete(urlChanged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(StMobileApp.LOG_TAG, "ssh: setup failed: ${e.javaClass.simpleName}: ${e.message}")
                error = setupErrorMessage(e, keyStage)
                hostKeyChange = (e as? SshError.HostKeyChanged)?.let { HostKeyCheck.Changed(it.expected, it.actual) }
                busy = false
            } finally {
                pw.fill('\u0000')
                if (!done) before.restore(settings)
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = LocalStPalette.current
        StSectionTitle("Connect to the tablet")
        Text(
            "The Control Center runs commands on the tablet over SSH. Enter the tablet's password once; " +
                "the app installs its own key and never stores the password.",
            color = Color(p.muted),
            style = MaterialTheme.typography.bodyMedium,
        )
        ConnectionFields(form, enabled = !busy, onChange = { form = it })
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Tablet password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            shape = StFieldShape,
            colors = stTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        keyProblem?.let { Text(it, color = Color(p.dangerBorder)) }
        error?.let { Text(it, color = Color(p.dangerBorder)) }
        hostKeyChange?.let { change ->
            if (allowTrust) {
                StButton("Trust new host key", { confirmTrust = true }, Modifier.fillMaxWidth(), enabled = !busy)
                if (confirmTrust) {
                    TrustHostKeyDialog(
                        check = change,
                        onConfirm = {
                            settings.knownHostKey = HostKeyPolicy.pinAfterConfirm(change)
                            confirmTrust = false
                            hostKeyChange = null
                            error = null
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.close() }
                        },
                        onDismiss = { confirmTrust = false },
                    )
                }
            } else if (onOpenSettings != null) {
                Text("You can review and trust the new key in Settings.", color = Color(p.muted), style = MaterialTheme.typography.bodySmall)
                StButton("Open Settings", onOpenSettings, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StButton("Connect", ::connect, Modifier.weight(1f), enabled = !busy && keyProblem == null, busy = busy)
            if (onCancel != null) {
                StButton("Cancel", onCancel, Modifier.weight(1f), enabled = !busy)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ConnectionFields(form: ConnectionForm, enabled: Boolean, onChange: (ConnectionForm) -> Unit) {
    val noCorrect = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)
    val colors = stTextFieldColors()
    OutlinedTextField(
        value = form.serverUrl, onValueChange = { onChange(form.copy(serverUrl = it)) },
        label = { Text("SillyTavern URL") }, singleLine = true, enabled = enabled,
        isError = !AppSettings.isValidServerUrl(form.serverUrl),
        keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Uri),
        shape = StFieldShape, colors = colors, modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.host, onValueChange = { onChange(form.copy(host = it)) },
            label = { Text("SSH host") }, singleLine = true, enabled = enabled,
            keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Uri),
            shape = StFieldShape, colors = colors, modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = form.port, onValueChange = { onChange(form.copy(port = it.filter(Char::isDigit).take(5))) },
            label = { Text("Port") }, singleLine = true, enabled = enabled,
            keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Number),
            shape = StFieldShape, colors = colors, modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = form.user, onValueChange = { onChange(form.copy(user = it)) },
        label = { Text("SSH user") }, singleLine = true, enabled = enabled,
        keyboardOptions = noCorrect, shape = StFieldShape, colors = colors, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.stUserDir, onValueChange = { onChange(form.copy(stUserDir = it)) },
        label = { Text("ST user folder") }, singleLine = true, enabled = enabled,
        isError = !ConnectionForm.isValidStUserDir(form.stUserDir.trim()),
        supportingText = if (ConnectionForm.isValidStUserDir(form.stUserDir.trim())) null else {
            { Text(ConnectionForm.ST_USER_DIR_ERROR) }
        },
        keyboardOptions = noCorrect, shape = StFieldShape, colors = colors, modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Confirmation before trusting a host key. Shows the exact fingerprint that will be pinned
 * ([HostKeyPolicy.pinAfterConfirm]); the caller pins that string, never clears the pin.
 */
@Composable
fun TrustHostKeyDialog(check: HostKeyCheck, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = when (check) {
        is HostKeyCheck.Changed ->
            "The tablet now presents a different SSH host key.\n\nPinned: ${check.expected}\nNow: ${check.actual}\n\n" +
                "Only trust it if you reinstalled Termux or SSH on the tablet or regenerated its host keys. " +
                "If you didn't change anything, someone may be intercepting the connection."
        is HostKeyCheck.Unpinned -> "Pin the tablet's host key?\n\n${check.presented}"
        is HostKeyCheck.Matches -> "The tablet's host key matches the pinned key.\n\n${check.fingerprint}"
    }
    StDialog(
        title = if (check is HostKeyCheck.Changed) "Trust the new host key?" else "Pin host key?",
        onDismiss = onDismiss,
        confirm = { StButton("Trust this key", onConfirm, Modifier.padding(horizontal = 8.dp)) },
        dismiss = { StButton("Cancel", onDismiss, Modifier.padding(horizontal = 8.dp)) },
    ) {
        Text(body, color = Color(LocalStPalette.current.text), style = MaterialTheme.typography.bodyMedium)
    }
}

internal val ConnectionFormSaver = listSaver<ConnectionForm, String>(
    save = { listOf(it.serverUrl, it.host, it.port, it.user, it.stUserDir) },
    restore = { ConnectionForm(it[0], it[1], it[2], it[3], it[4]) },
)

/** The SSH settings [ConnectionForm.saveSsh] writes, with the host key pin it may clear. */
private data class SshFields(val host: String, val port: Int, val user: String, val stUserDir: String, val hostKey: String?) {
    fun restore(settings: AppSettings) {
        settings.sshHost = host
        settings.sshPort = port
        settings.sshUser = user
        settings.stUserDir = stUserDir
        settings.knownHostKey = hostKey
    }

    companion object {
        fun of(s: AppSettings) = SshFields(s.sshHost, s.sshPort, s.sshUser, s.stUserDir, s.knownHostKey)
    }
}
