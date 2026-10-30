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

/**
 * The probe and action commands. Over [Probes] in the app; faked in tests. Each throws
 * [IllegalArgumentException] if a configured SillyTavern folder is invalid.
 */
interface ProbeSource {
    fun dashboard(): RemoteCommand
    fun whitelist(): RemoteCommand
    /** `stpanel <sub>`, detached, with [input] on its stdin. */
    fun action(sub: String, input: String?): RemoteCommand
}

/** Reads the folders from settings on every call, so a change in Settings applies on the next poll. */
class AndroidProbeSource(private val context: Context, private val settings: AppSettings) : ProbeSource {
    private val stDir = "" // Task 5: settings.stDir
    override fun dashboard() = Probes.dashboard(context, stDir, settings.stUserDir)
    override fun whitelist() = Probes.whitelist(context, stDir)
    override fun action(sub: String, input: String?) = Probes.action(context, sub, stDir, input)
}

enum class PanelAction(val label: String, val subcommand: String) {
    Start("Start", "start"),
    Stop("Stop", "stop"),
    Restart("Restart", "restart"),
    Update("Update", "update"),
    Backup("Backup", "backup"),
    CleanCache("Clean cache", "clean"),
}

/** A whitelist-mode toggle waiting for confirmation. [from] is the mode the dialog showed. */
data class WhitelistToggle(val from: Boolean, val enable: Boolean, val warning: String)

data class PanelUi(
    val dashboard: DashboardState? = null,
    val error: String? = null,
    /** An action is running, including its post-action dashboard refresh. */
    val busy: PanelAction? = null,
    val pendingConfirm: PanelAction? = null,
    val lastResult: ActionResult? = null,
    val restartSuggested: Boolean = false,
    val whitelist: WhitelistState? = null,
    /** [error] is a changed SSH host key; the screen links to Settings → "Trust new host key". */
    val hostKeyChanged: Boolean = false,
    /** A whitelist change is running. Actions are blocked meanwhile, like [busy]. */
    val whitelistBusy: Boolean = false,
    /** config.yaml is being (re-)read; [whitelist] may be stale until this is false. */
    val whitelistLoading: Boolean = false,
    /** Reading the whitelist failed; the dialog shows this with Retry. */
    val whitelistError: String? = null,
    /** Outcome of the last whitelist change, shown inside the dialog. */
    val whitelistMessage: String? = null,
    val pendingWhitelistToggle: WhitelistToggle? = null,
    val lastAction: ActionResult? = null,
) {
    /** Nothing this app is waiting for runs, so probes may: dashboard.sh itself holds off its `git fetch`. */
    val probesAllowed: Boolean get() = busy == null && !whitelistBusy

    /** An action keeps running on the tablet after this app stopped waiting for it (a timeout, or an earlier process). */
    val tabletBusy: Boolean get() = dashboard?.runningAction != null

    val actionsEnabled: Boolean get() = probesAllowed && !tabletBusy

    /** [action] runs: here, or still on the tablet. */
    fun isRunning(action: PanelAction): Boolean = busy == action || (busy == null && dashboard?.runningAction == action.subcommand)

    /** The mode switch may be used: fresh state, nothing running. */
    val whitelistToggleEnabled: Boolean
        get() = whitelist != null && !whitelistLoading && whitelistError == null && actionsEnabled
}

/** HTTP answered at all (any status), so the server is up and serving, not just a live PID. */
val DashboardState.serving: Boolean get() = online && httpCode?.matches(Regex("[1-5]\\d\\d")) == true

private val IPV4_ONLY = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private fun ipv4ToInt(ip: String): Int? {
    if (!IPV4_ONLY.matches(ip)) return null
    val parts = ip.split('.').map { it.toInt() }
    if (parts.any { it > 255 }) return null
    return parts.fold(0) { acc, p -> (acc shl 8) or p }
}

/** True if [ip] equals an entry, or an IPv4 CIDR entry contains it. IPv6 subnets are not expanded. */
internal fun isCoveredBy(ip: String, entries: List<String>): Boolean = entries.any { entry ->
    if (entry == ip) return@any true
    val slash = entry.indexOf('/')
    if (slash < 0) return@any false
    val net = ipv4ToInt(entry.substring(0, slash)) ?: return@any false
    val bits = entry.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..32 } ?: return@any false
    val addr = ipv4ToInt(ip) ?: return@any false
    val mask = if (bits == 0) 0 else -1 shl (32 - bits)
    (addr and mask) == (net and mask)
}

/**
 * Control Center state. Activity-scoped. Commands that change the server run in [actions]' app-wide
 * scope with `signalOnCancel = false` (Ruling 28): nothing UI-bound can cancel them, and even a timeout
 * only closes the channel instead of sending TERM to a half-done restart or update. This ViewModel
 * awaits the result to update busy state and the result line; a new instance adopts an action that is
 * still running.
 *
 * Probes never run under an action this app awaits (Ruling 22): a failing probe can drop the shared SSH
 * connection. One that outlived its wait (a timeout) is still marked on the tablet (Probes.wrapDetached):
 * the dashboard reports it, buttons stay off until it ends, and dashboard.sh skips its `git fetch` meanwhile.
 *
 * An Update that changed the code only suggests a Restart (Ruling 27): it never reloads Chat. The
 * asset cache is namespaced by `<pkgVersion>@<gitRevision>`, so the next load after the restart uses fresh files.
 *
 * @param onServerRestarted called once when a fresh dashboard shows the server serving again after a
 *   Start/Restart or after the server was seen down (also if it was started outside the app); wired to
 *   WebViewHolder.reload() so the Chat tab recovers.
 * @param onServerDown called once when the server is seen down: a successful Stop, or a fresh dashboard
 *   showing the process gone (online=0 / no pid). A running process that misses the 1 s HTTP check
 *   never counts (a busy server streaming a generation can miss it; marking down would reload Chat
 *   and kill the generation), it only shows on the Panel's health card. Wired to
 *   WebViewHolder.markServerDown() so Chat shows the Unreachable screen instead of a stale page.
 */
class PanelViewModel(
    private val shell: RemoteShell,
    private val probes: ProbeSource,
    private val actions: ActionTracker,
    private val onServerRestarted: () -> Unit = {},
    private val onServerDown: (String) -> Unit = {},
) : ViewModel() {
    private val _ui = MutableStateFlow(PanelUi())
    val ui: StateFlow<PanelUi> = _ui

    /** Serialises probes; actions take it briefly so they never start under an in-flight probe. */
    private val probeLock = Mutex()
    private var pollJob: Job? = null

    private var appForeground = true
    private var awaitServingJob: Job? = null
    /** Probes left in the current Start/Restart wait; > 0 means a wait is pending (maybe paused). */
    private var servingTriesLeft = 0

    /** A Start/Restart succeeded; reload Chat when a fresh dashboard shows the server serving. */
    private var reloadPending = false
    /** [onServerDown] was called and the server hasn't been seen serving since. */
    private var downNotified = false

    init {
        // Recreated while an action runs (it outlives the old ViewModel): show it as busy and take its result.
        actions.inFlight()?.let { flight ->
            _ui.update { it.copy(busy = flight.action) }
            viewModelScope.launch { finish(flight) }
        }
    }

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
                refreshNow() // skipped while an action runs
                delay(POLL_MS)
            }
        }
    }

    /**
     * Activity started/stopped. The post-Start/Restart wait pauses in the background (it keeps its
     * remaining budget) and resumes in the foreground; switching tabs does not pause it.
     */
    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        if (!foreground) {
            awaitServingJob?.cancel()
            awaitServingJob = null
        } else if (servingTriesLeft > 0) {
            launchAwaitServing()
        }
    }

    /**
     * The Chat page finished loading against a live server (however it got there, e.g. its own
     * Retry). Nothing left to recover: drop a pending down/Start reload so a later serving poll
     * doesn't reload a working chat (and kill a generation in progress).
     */
    fun onChatReady() {
        downNotified = false
        reloadPending = false
        servingTriesLeft = 0
        awaitServingJob?.cancel()
        awaitServingJob = null
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

    /** (Re-)reads mode and entries for the whitelist dialog (node startup is slow, so not part of the poll). */
    fun loadWhitelist() {
        if (!_ui.value.actionsEnabled) {
            _ui.update { it.copy(whitelistMessage = null, whitelistError = "Wait for the running action to finish, then retry.") }
            return
        }
        // Mark loading now, not when the coroutine starts, so the switch is disabled immediately.
        _ui.update { it.copy(whitelistMessage = null, whitelistLoading = true, whitelistError = null) }
        viewModelScope.launch { loadWhitelistNow() }
    }

    fun addWhitelistEntry(ip: String) {
        val entry = ip.trim()
        if (!isValidIpOrCidr(entry)) {
            _ui.update { it.copy(error = "\"$ip\" is not a valid IP address or CIDR subnet", hostKeyChanged = false) }
            return
        }
        if (!_ui.value.actionsEnabled) return
        _ui.update { it.copy(whitelistBusy = true, whitelistMessage = null) }
        viewModelScope.launch {
            try {
                // stpanel whitelist menu: 2 = add, then the address. stpanel puts it in a node -e string, hence the check above.
                execWhitelist("2\n$entry\n") { out ->
                    when (parseWhitelistAdd(out)) {
                        WhitelistAdd.Added -> WhitelistReply(ActionResult.Ok("Added $entry to the whitelist"))
                        WhitelistAdd.AlreadyListed -> WhitelistReply(ActionResult.Ok("$entry is already in the whitelist"), changed = false)
                        null -> null
                    }
                }
                loadWhitelistNow(force = true)
            } finally {
                _ui.update { it.copy(whitelistBusy = false) }
            }
        }
    }

    /**
     * Asks to flip whitelist mode; nothing runs until [confirmWhitelistToggle]. Ignored unless the
     * shown state is fresh (loaded, not reloading) and nothing else is running.
     */
    fun toggleWhitelist() {
        val s = _ui.value
        val wl = s.whitelist ?: return
        if (!s.whitelistToggleEnabled) return
        val enable = !wl.enabled
        val warning = if (!enable) {
            "Anyone who can reach the server will have access. If user accounts and basic auth are also off, " +
                "SillyTavern refuses to start."
        } else {
            val lastIp = s.dashboard?.lastClient?.substringBefore(' ')?.takeIf { isValidIpOrCidr(it) }
            "Only the addresses in the list will be able to connect. Make sure your phone's address is in the list." +
                if (lastIp != null && !isCoveredBy(lastIp, wl.entries)) {
                    " The last client, $lastIp, is not covered by any entry."
                } else ""
        }
        _ui.update { it.copy(pendingWhitelistToggle = WhitelistToggle(from = wl.enabled, enable = enable, warning = warning)) }
    }

    fun dismissWhitelistToggle() = _ui.update { it.copy(pendingWhitelistToggle = null) }

    /**
     * stpanel's toggle is relative (`!cur`), so re-read config.yaml first and toggle only if the mode
     * is still what the dialog showed.
     */
    fun confirmWhitelistToggle() {
        val pending = _ui.value.pendingWhitelistToggle ?: return
        _ui.update { it.copy(pendingWhitelistToggle = null) }
        if (!_ui.value.whitelistToggleEnabled) return
        _ui.update { it.copy(whitelistBusy = true, whitelistMessage = null) }
        viewModelScope.launch {
            try {
                val fresh = loadWhitelistNow(force = true)
                when {
                    fresh == null -> _ui.update { it.copy(whitelistMessage = "Nothing changed: couldn't re-read config.yaml.") }
                    fresh.enabled != pending.from -> {
                        val msg = "Whitelist mode changed on the tablet meanwhile. Nothing was toggled; check and try again."
                        _ui.update { it.copy(whitelistMessage = msg, lastResult = ActionResult.Failed(msg)) }
                    }
                    else -> {
                        // stpanel whitelist menu: 1 = toggle whitelist mode.
                        execWhitelist("1\n") { out ->
                            Regex("""toggled to:\s*(ENABLED|DISABLED)""").find(out)
                                ?.let { WhitelistReply(ActionResult.Ok("Whitelist mode ${if (it.groupValues[1] == "ENABLED") "on" else "off"}")) }
                        }
                        loadWhitelistNow(force = true)
                    }
                }
            } finally {
                _ui.update { it.copy(whitelistBusy = false) }
            }
        }
    }

    /** A whitelist change stpanel reported; [changed] false means config.yaml was left as it was. */
    private class WhitelistReply(val result: ActionResult.Ok, val changed: Boolean = true)

    /** Runs `stpanel whitelist` with [stdin]; reports the result and suggests a restart if config.yaml changed. */
    private suspend fun execWhitelist(stdin: String, interpret: (String) -> WhitelistReply?) {
        probeLock.withLock { } // don't start under an in-flight probe
        var changed = false
        val result = try {
            val c = probes.action("whitelist", stdin)
            val r = actions.runDetached { shell.exec(c.command, c.stdin, ACTION_TIMEOUT_MS, signalOnCancel = false) }
            stpanelMissing(r) ?: interpret(stripAnsi(r.stdout))?.let { changed = it.changed; it.result } ?: ActionResult.Failed(
                "Whitelist not changed" + (firstLine(r.stderr)?.let { ": $it" } ?: ""),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
            ActionResult.Failed("Whitelist not changed: ${describe(e)}")
        }
        _ui.update {
            it.copy(
                lastResult = result,
                lastAction = result,
                whitelistMessage = result.message,
                restartSuggested = it.restartSuggested || changed,
            )
        }
    }

    private fun run(action: PanelAction) {
        _ui.update { it.copy(busy = action) }
        viewModelScope.launch {
            val flight = try {
                probeLock.withLock { } // don't start under an in-flight probe
                val stdin = if (action == PanelAction.CleanCache) "y\n" else null
                val timeout = if (action == PanelAction.Update || action == PanelAction.Backup) LONG_TIMEOUT_MS else ACTION_TIMEOUT_MS
                actions.start(action) {
                    // Built in here, so an invalid folder setting ends as this action's failure.
                    val c = probes.action(action.subcommand, stdin)
                    shell.exec(c.command, c.stdin, timeout, signalOnCancel = false)
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(busy = null) }
                throw e
            }
            finish(flight)
        }
    }

    /** Awaits a running action (started here or by an earlier ViewModel), then reports and refreshes. */
    private suspend fun finish(flight: ActionTracker.InFlight) {
        val action = flight.action
        try {
            val result = try {
                val r = flight.result.await()
                stpanelMissing(r) ?: interpret(action, r)
            } catch (e: CancellationException) {
                throw e // this ViewModel is gone; the action itself keeps running
            } catch (_: SshError.Timeout) {
                // Actions run detached (Probes.wrapDetached), so giving up here doesn't stop it.
                ActionResult.StillRunning("${action.label} is taking a while and keeps running on the tablet. The buttons come back when it's done.")
            } catch (e: Exception) {
                showError(e)
                ActionResult.Failed("${action.label} failed: ${describe(e)}")
            }
            _ui.update { it.copy(lastResult = result, lastAction = result) }
            if (result is ActionResult.Ok) afterSuccess(action, result)
            // Still busy here, so no poll can interleave; polling resumes after this.
            refreshNow(force = true)
        } finally {
            _ui.update { it.copy(busy = null) }
        }
        if (reloadPending) startAwaitServing()
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
            // New code runs only after a restart; suggest one. Never reload Chat here (Ruling 27).
            PanelAction.Update -> if (result.message != UP_TO_DATE) _ui.update { it.copy(restartSuggested = true) }
            PanelAction.Start -> {
                _ui.update { it.copy(restartSuggested = false) }
                reloadPending = true
            }
            PanelAction.Restart -> {
                _ui.update { it.copy(restartSuggested = false) }
                // The loaded page belongs to the old process: show Unreachable until the new one serves,
                // then reload (the reload callback skips a Chat that is Ready).
                markDown("SillyTavern is restarting")
                reloadPending = true
            }
            PanelAction.Stop -> markDown(STOPPED)
            else -> Unit
        }
    }

    /** Ruling 18: wait (about [AWAIT_SERVING_TRIES] × [AWAIT_SERVING_INTERVAL_MS]) for a fresh "serving" dashboard. */
    private fun startAwaitServing() {
        servingTriesLeft = AWAIT_SERVING_TRIES
        awaitServingJob?.cancel()
        awaitServingJob = null
        if (appForeground) launchAwaitServing()
    }

    private fun launchAwaitServing() {
        if (awaitServingJob?.isActive == true) return
        awaitServingJob = viewModelScope.launch {
            // Only fresh, successful probes count (see observe); a failed one never uses the old state.
            while (servingTriesLeft > 0 && reloadPending) {
                delay(AWAIT_SERVING_INTERVAL_MS)
                servingTriesLeft--
                refreshNow()
            }
        }
    }

    /**
     * Chat follows the server: down once when the process is gone, one reload when it serves again.
     * A live process that misses the HTTP check changes nothing here (see [onServerDown]).
     */
    private fun observe(d: DashboardState) {
        when {
            d.serving -> if (reloadPending || downNotified) {
                reloadPending = false
                downNotified = false
                servingTriesLeft = 0
                onServerRestarted()
            }
            !d.online || d.pid == null -> markDown(STOPPED)
        }
    }

    private fun markDown(reason: String) {
        if (downNotified) return
        downNotified = true
        onServerDown(reason)
    }

    /**
     * Probes the dashboard and returns the freshly parsed state, or null if the probe failed, its
     * output had no `online=`, or it was skipped because an awaited action is running (unless [force]).
     */
    private suspend fun refreshNow(force: Boolean = false): DashboardState? {
        if (!force && !_ui.value.probesAllowed) return null
        return probeLock.withLock {
            if (!force && !_ui.value.probesAllowed) return@withLock null
            try {
                val c = probes.dashboard()
                val r = shell.exec(c.command, c.stdin, PROBE_TIMEOUT_MS)
                if (!stripAnsi(r.stdout).lineSequence().any { it.startsWith("online=") }) {
                    val why = firstLine(r.stderr) ?: "exit ${r.exitCode}"
                    _ui.update { it.copy(error = "Couldn't read the server status ($why)", hostKeyChanged = false) }
                    null
                } else {
                    val d = parseDashboard(r.stdout)
                    _ui.update { it.copy(dashboard = d, error = null, hostKeyChanged = false) }
                    observe(d)
                    d
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
                null
            }
        }
    }

    /** Reads config.yaml's whitelist. Errors go to [PanelUi.whitelistError] (shown in the dialog). */
    private suspend fun loadWhitelistNow(force: Boolean = false): WhitelistState? {
        _ui.update { it.copy(whitelistLoading = true, whitelistError = null) }
        return probeLock.withLock {
            try {
                if (!force && !_ui.value.actionsEnabled) {
                    _ui.update { it.copy(whitelistError = "Wait for the running action to finish, then retry.") }
                    return@withLock null
                }
                val c = probes.whitelist()
                val r = shell.exec(c.command, c.stdin, PROBE_TIMEOUT_MS)
                if (!stripAnsi(r.stdout).lineSequence().any { it.startsWith("mode=") }) {
                    val why = firstLine(r.stderr) ?: "exit ${r.exitCode}"
                    _ui.update { it.copy(whitelistError = "Couldn't read the whitelist ($why)") }
                    null
                } else {
                    val w = parseWhitelist(r.stdout)
                    _ui.update { it.copy(whitelist = w) }
                    w
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(whitelistError = "Couldn't read the whitelist: ${describe(e)}") }
                null
            } finally {
                _ui.update { it.copy(whitelistLoading = false) }
            }
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
        /** 45 × 2 s ≈ 90 s of foreground waiting for the server after Start/Restart. */
        const val AWAIT_SERVING_TRIES = 45
        const val AWAIT_SERVING_INTERVAL_MS = 2_000L
        private const val UP_TO_DATE = "Already up to date"
        private const val STOPPED = "SillyTavern is stopped"
        val NEEDS_CONFIRM = setOf(PanelAction.Stop, PanelAction.CleanCache, PanelAction.Update)
    }
}
