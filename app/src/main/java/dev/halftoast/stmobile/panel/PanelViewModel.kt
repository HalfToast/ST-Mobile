package dev.halftoast.stmobile.panel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.halftoast.stmobile.settings.AppSettings
import dev.halftoast.stmobile.ssh.ExecResult
import dev.halftoast.stmobile.ssh.RemoteShell
import dev.halftoast.stmobile.ssh.SshError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The probe commands, as `(command, stdin)` pairs. Over [Probes] in the app; faked in tests. */
interface ProbeSource {
    /** Throws [IllegalArgumentException] if the configured SillyTavern user folder is invalid. */
    fun dashboard(): Pair<String, String>
    fun whitelist(): Pair<String, String>
}

/** Reads the user folder from settings on every call, so a change in Settings applies on the next poll. */
class AndroidProbeSource(private val context: Context, private val settings: AppSettings) : ProbeSource {
    override fun dashboard() = Probes.dashboard(context, settings.stUserDir)
    override fun whitelist() = Probes.whitelist(context)
}

enum class PanelAction(val label: String, val subcommand: String) {
    Start("Start", "start"),
    Stop("Stop", "stop"),
    Restart("Restart", "restart"),
    Update("Update", "update"),
    Backup("Backup", "backup"),
    CleanCache("Clean cache", "clean"),
}

data class PanelUi(
    val dashboard: DashboardState? = null,
    val error: String? = null,
    val busy: PanelAction? = null,
    val pendingConfirm: PanelAction? = null,
    val lastResult: ActionResult? = null,
    val restartSuggested: Boolean = false,
    val whitelist: WhitelistState? = null,
    /** [error] is a changed SSH host key; the screen links to Settings → "Trust new host key". */
    val hostKeyChanged: Boolean = false,
    /** A whitelist change is running. Actions are blocked meanwhile, like [busy]. */
    val whitelistBusy: Boolean = false,
) {
    val actionsEnabled: Boolean get() = busy == null && !whitelistBusy
}

/** HTTP answered at all (any status), so the server is up and serving, not just a live PID. */
val DashboardState.serving: Boolean get() = online && httpCode?.matches(Regex("[1-5]\\d\\d")) == true

/**
 * Control Center state. Activity-scoped: actions run in [viewModelScope] so a Backup or Update
 * keeps going across tab switches and backgrounding (cancelling exec would TERM the remote command).
 *
 * @param onUpdated called after an Update that changed the code; wired to WebViewHolder.revalidate().
 * @param onServerRestarted called once after a Start/Restart, when the dashboard shows the server
 *   serving again; wired to WebViewHolder.reload() so the Chat tab recovers.
 */
class PanelViewModel(
    private val shell: RemoteShell,
    private val probes: ProbeSource,
    private val onUpdated: () -> Unit,
    private val onServerRestarted: () -> Unit = {},
) : ViewModel() {
    private val _ui = MutableStateFlow(PanelUi())
    val ui: StateFlow<PanelUi> = _ui

    private val refreshLock = Mutex()
    private var pollJob: Job? = null
    private var awaitOnlineJob: Job? = null

    /** Polls the dashboard every [POLL_MS] while true. The screen sets it from tab visibility and lifecycle. */
    fun setVisible(visible: Boolean) {
        if (!visible) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshNow()
                delay(POLL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    fun request(action: PanelAction) {
        if (!_ui.value.actionsEnabled) return
        if (action in NEEDS_CONFIRM) _ui.update { it.copy(pendingConfirm = action) } else run(action)
    }

    fun confirm() {
        val action = _ui.value.pendingConfirm ?: return
        _ui.update { it.copy(pendingConfirm = null) }
        if (_ui.value.actionsEnabled) run(action)
    }

    fun dismiss() = _ui.update { it.copy(pendingConfirm = null) }

    /** The snackbar showed [PanelUi.lastResult]. */
    fun resultShown() = _ui.update { it.copy(lastResult = null) }

    fun dismissRestartSuggestion() = _ui.update { it.copy(restartSuggested = false) }

    /** Loads mode and entries for the whitelist dialog (node startup is slow, so not part of the poll). */
    fun loadWhitelist() {
        viewModelScope.launch { loadWhitelistNow() }
    }

    fun addWhitelistEntry(ip: String) {
        val entry = ip.trim()
        if (!isValidIpOrCidr(entry)) {
            _ui.update { it.copy(error = "\"$ip\" is not a valid IP address or CIDR subnet", hostKeyChanged = false) }
            return
        }
        // stpanel whitelist menu: 2 = add, then the address. stpanel puts it in a node -e string, hence the check above.
        runWhitelist("2\n$entry\n") { out ->
            if ("Added" in out) ActionResult.Ok("Added $entry to the whitelist") else null
        }
    }

    /** stpanel whitelist menu: 1 = toggle whitelist mode. */
    fun toggleWhitelist() = runWhitelist("1\n") { out ->
        Regex("""toggled to:\s*(ENABLED|DISABLED)""").find(out)
            ?.let { ActionResult.Ok("Whitelist mode ${it.groupValues[1].lowercase()}") }
    }

    private fun runWhitelist(stdin: String, interpret: (String) -> ActionResult.Ok?) {
        if (!_ui.value.actionsEnabled) return
        _ui.update { it.copy(whitelistBusy = true) }
        viewModelScope.launch {
            val result = try {
                val r = shell.exec(Probes.action("whitelist"), stdin, ACTION_TIMEOUT_MS)
                stpanelMissing(r) ?: interpret(stripAnsi(r.stdout)) ?: ActionResult.Failed(
                    "Whitelist not changed" + (firstLine(r.stderr)?.let { ": $it" } ?: ""),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
                ActionResult.Failed("Whitelist not changed: ${describe(e)}")
            } finally {
                _ui.update { it.copy(whitelistBusy = false) }
            }
            _ui.update {
                it.copy(lastResult = result, restartSuggested = it.restartSuggested || result is ActionResult.Ok)
            }
            loadWhitelistNow()
        }
    }

    private fun run(action: PanelAction) {
        _ui.update { it.copy(busy = action) }
        viewModelScope.launch {
            val stdin = if (action == PanelAction.CleanCache) "y\n" else null
            val timeout = if (action == PanelAction.Update || action == PanelAction.Backup) LONG_TIMEOUT_MS else ACTION_TIMEOUT_MS
            val result = try {
                val r = shell.exec(Probes.action(action.subcommand), stdin, timeout)
                stpanelMissing(r) ?: interpret(action, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
                ActionResult.Failed("${action.label} failed: ${describe(e)}")
            } finally {
                _ui.update { it.copy(busy = null) }
            }
            _ui.update { it.copy(lastResult = result) }
            if (result is ActionResult.Ok) afterSuccess(action, result)
            refreshNow()
        }
    }

    private fun interpret(action: PanelAction, r: ExecResult): ActionResult = when (action) {
        PanelAction.Backup -> parseBackupResult(r.stdout)
        PanelAction.CleanCache -> parseCleanResult(r.stdout)
        PanelAction.Update -> parseUpdateResult(r.stdout + "\n" + r.stderr, r.exitCode)
        PanelAction.Start, PanelAction.Stop, PanelAction.Restart ->
            if (r.exitCode == 0) {
                ActionResult.Ok(
                    when (action) {
                        PanelAction.Start -> "Start requested"
                        PanelAction.Stop -> "Server stopped"
                        else -> "Restart requested"
                    },
                )
            } else {
                ActionResult.Failed("${action.label} failed (exit ${r.exitCode})" + (firstLine(r.stderr)?.let { ": $it" } ?: ""))
            }
    }

    private fun afterSuccess(action: PanelAction, result: ActionResult.Ok) {
        when (action) {
            PanelAction.Update -> if (result.message != UP_TO_DATE) {
                onUpdated()
                _ui.update { it.copy(restartSuggested = true) }
            }
            PanelAction.Start, PanelAction.Restart -> {
                _ui.update { it.copy(restartSuggested = false) }
                awaitServing()
            }
            else -> Unit
        }
    }

    /** Ruling 18: once the server serves again after Start/Restart, reload the Chat tab once. */
    private fun awaitServing() {
        awaitOnlineJob?.cancel()
        awaitOnlineJob = viewModelScope.launch {
            repeat(AWAIT_ONLINE_TRIES) { i ->
                if (i > 0) delay(AWAIT_ONLINE_INTERVAL_MS)
                refreshNow()
                if (_ui.value.dashboard?.serving == true) {
                    onServerRestarted()
                    return@launch
                }
            }
        }
    }

    private suspend fun refreshNow() = refreshLock.withLock {
        try {
            val (command, script) = probes.dashboard()
            val r = shell.exec(command, script, PROBE_TIMEOUT_MS)
            if (!stripAnsi(r.stdout).lineSequence().any { it.startsWith("online=") }) {
                val why = firstLine(r.stderr) ?: "exit ${r.exitCode}"
                _ui.update { it.copy(error = "Couldn't read the server status ($why)", hostKeyChanged = false) }
            } else {
                val d = parseDashboard(r.stdout)
                _ui.update { it.copy(dashboard = d, error = null, hostKeyChanged = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
        }
    }

    private suspend fun loadWhitelistNow() {
        try {
            val (command, script) = probes.whitelist()
            val r = shell.exec(command, script, PROBE_TIMEOUT_MS)
            if (!stripAnsi(r.stdout).lineSequence().any { it.startsWith("mode=") }) {
                val why = firstLine(r.stderr) ?: "exit ${r.exitCode}"
                _ui.update { it.copy(error = "Couldn't read the whitelist ($why)", hostKeyChanged = false) }
            } else {
                _ui.update { it.copy(whitelist = parseWhitelist(r.stdout)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun showError(e: Exception) =
        _ui.update { it.copy(error = describe(e), hostKeyChanged = e is SshError.HostKeyChanged) }

    private fun describe(e: Exception): String = when (e) {
        is SshError.HostKeyChanged -> "The tablet's SSH host key changed. If you expected this, trust the new key in Settings."
        is SshError.Unreachable, is SshError.Timeout ->
            "${e.message}. Check that Tailscale is connected on this phone and the tablet is awake."
        is SshError -> e.message ?: "SSH error"
        is IllegalArgumentException -> "The SillyTavern user folder in Settings is invalid"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun stpanelMissing(r: ExecResult): ActionResult.Failed? =
        if (r.exitCode == 127) ActionResult.Failed("stpanel was not found on the tablet") else null

    private fun firstLine(s: String): String? =
        stripAnsi(s).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120)

    companion object {
        const val POLL_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 20_000L
        const val ACTION_TIMEOUT_MS = 60_000L
        const val LONG_TIMEOUT_MS = 300_000L
        const val AWAIT_ONLINE_TRIES = 30
        const val AWAIT_ONLINE_INTERVAL_MS = 2_000L
        private const val UP_TO_DATE = "Already up to date"
        val NEEDS_CONFIRM = setOf(PanelAction.Stop, PanelAction.CleanCache, PanelAction.Update)
    }
}
