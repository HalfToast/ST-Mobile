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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.ssh.HostKeyPolicy
import dev.halftoast.stmobile.ssh.KeyInstaller
import dev.halftoast.stmobile.ssh.KeyManager
import dev.halftoast.stmobile.ssh.SshError
import dev.halftoast.stmobile.ssh.SshRemoteShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editable connection fields shared by Setup and Settings (everything but the password). */
data class ConnectionForm(
    val serverUrl: String,
    val host: String,
    val port: String,
    val user: String,
    val stUserDir: String,
) {
    /** First problem with the form, or null if it can be saved. */
    fun validate(): String? = when {
        !AppSettings.isValidServerUrl(serverUrl) -> "Server URL must start with http:// or https://"
        host.isBlank() || host.any { it.isWhitespace() } -> "Enter the tablet's SSH host"
        port.toIntOrNull()?.let { it in 1..65535 } != true -> "Port must be a number from 1 to 65535"
        user.isBlank() || user.any { it.isWhitespace() } -> "Enter the SSH user"
        stUserDir.isBlank() || '/' in stUserDir || stUserDir == "." || stUserDir == ".." ->
            "ST user folder must be a folder name, like default-user"
        else -> null
    }

    /** Saves the SSH fields; a different host or port unpins the old host key (pins are per host). */
    fun saveSsh(settings: AppSettings) {
        val newHost = host.trim()
        val newPort = port.trim().toInt()
        if (newHost != settings.sshHost || newPort != settings.sshPort) settings.knownHostKey = null
        settings.sshHost = newHost
        settings.sshPort = newPort
        settings.sshUser = user.trim()
        settings.stUserDir = stUserDir.trim()
    }

    /** Saves the server URL; returns true if it changed. */
    fun saveServerUrl(settings: AppSettings): Boolean {
        val url = serverUrl.trim()
        if (url == settings.serverUrl) return false
        settings.serverUrl = url
        return true
    }

    companion object {
        /** Suggested user for a blank field: Termux's user on HalfToast's tablet. Editable. */
        const val SUGGESTED_SSH_USER = "u0_a123"

        fun from(settings: AppSettings) = ConnectionForm(
            serverUrl = settings.serverUrl,
            host = settings.sshHost,
            port = settings.sshPort.toString(),
            user = settings.sshUser.ifBlank { SUGGESTED_SSH_USER },
            stUserDir = settings.stUserDir,
        )
    }
}

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
) {
    val activity = LocalActivity.current as? ComponentActivity
    val scope = rememberCoroutineScope()
    var form by rememberSaveable(stateSaver = ConnectionFormSaver) { mutableStateOf(ConnectionForm.from(settings)) }
    // Deliberately not rememberSaveable: the password must never reach the saved instance state.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyChanged by remember { mutableStateOf(false) }
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
        hostKeyChanged = false
        busy = true
        val f = form
        scope.launch {
            var keyStage = false
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
                busy = false
                activity?.let { KeepAliveService.requestKeepAlivePermissions(it) }
                onComplete(urlChanged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(StMobileApp.LOG_TAG, "ssh: setup failed: ${e.javaClass.simpleName}: ${e.message}")
                error = setupErrorMessage(e, keyStage)
                hostKeyChanged = e is SshError.HostKeyChanged
                busy = false
            } finally {
                pw.fill('\u0000')
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
        Text("Connect to the tablet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The Control Center runs commands on the tablet over SSH. Enter the tablet's password once; " +
                "the app installs its own key and never stores the password.",
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
            modifier = Modifier.fillMaxWidth(),
        )
        keyProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (hostKeyChanged) TrustNewHostKeyButton(settings, shell, onCleared = { hostKeyChanged = false; error = null })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::connect, enabled = !busy && keyProblem == null) { Text("Connect") }
            if (busy) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (onCancel != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ConnectionFields(form: ConnectionForm, enabled: Boolean, onChange: (ConnectionForm) -> Unit) {
    val noCorrect = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)
    OutlinedTextField(
        value = form.serverUrl, onValueChange = { onChange(form.copy(serverUrl = it)) },
        label = { Text("SillyTavern URL") }, singleLine = true, enabled = enabled,
        isError = !AppSettings.isValidServerUrl(form.serverUrl),
        keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.host, onValueChange = { onChange(form.copy(host = it)) },
            label = { Text("SSH host") }, singleLine = true, enabled = enabled,
            keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = form.port, onValueChange = { onChange(form.copy(port = it.filter(Char::isDigit).take(5))) },
            label = { Text("Port") }, singleLine = true, enabled = enabled,
            keyboardOptions = noCorrect.copy(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = form.user, onValueChange = { onChange(form.copy(user = it)) },
        label = { Text("SSH user") }, singleLine = true, enabled = enabled,
        keyboardOptions = noCorrect, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.stUserDir, onValueChange = { onChange(form.copy(stUserDir = it)) },
        label = { Text("ST user folder") }, singleLine = true, enabled = enabled,
        keyboardOptions = noCorrect, modifier = Modifier.fillMaxWidth(),
    )
}

/** Clears the pinned host key after confirmation, so the next connection pins what the tablet presents. */
@Composable
fun TrustNewHostKeyButton(settings: AppSettings, shell: SshRemoteShell, onCleared: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }) { Text("Trust new host key") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Trust a new host key?") },
            text = {
                Text(
                    "Only do this if you reinstalled Termux or SSH on the tablet or regenerated its host keys. " +
                        "The next connection will trust whatever key the tablet presents. If you didn't change " +
                        "anything, someone may be intercepting the connection.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.knownHostKey = null
                    shell.close()
                    confirm = false
                    onCleared()
                }) { Text("Trust new key") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

internal val ConnectionFormSaver = listSaver<ConnectionForm, String>(
    save = { listOf(it.serverUrl, it.host, it.port, it.user, it.stUserDir) },
    restore = { ConnectionForm(it[0], it[1], it[2], it[3], it[4]) },
)
