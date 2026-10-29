package dev.halftoast.stmobile.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.halftoast.stmobile.panel.CharacterStat
import dev.halftoast.stmobile.panel.parseStats
import dev.halftoast.stmobile.ssh.RemoteShell
import dev.halftoast.stmobile.ssh.SshError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUi(
    val stats: List<CharacterStat>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hostKeyChanged: Boolean = false,
)

/**
 * Activity-scoped: the python probe takes a few seconds, and a tab switch must not cancel it halfway
 * (cancelling exec would TERM the remote command).
 *
 * @param command builds the `(command, stdin)` pair; reads the user folder from settings on each call.
 */
class StatsViewModel(
    private val shell: RemoteShell,
    private val command: () -> Pair<String, String>,
) : ViewModel() {
    private val _ui = MutableStateFlow(StatsUi())
    val ui: StateFlow<StatsUi> = _ui
    private var job: Job? = null

    /** Starts a load unless one is already running. */
    fun refresh() {
        if (job?.isActive == true) return
        _ui.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            try {
                val (cmd, stdin) = command()
                val r = shell.exec(cmd, stdin)
                if (r.exitCode != 0) {
                    val first = r.stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                    _ui.update {
                        it.copy(error = "Couldn't read the stats" + (first?.let { f -> ": $f" } ?: ""), hostKeyChanged = false)
                    }
                } else {
                    _ui.update { it.copy(stats = parseStats(r.stdout), error = null, hostKeyChanged = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = describe(e), hostKeyChanged = e is SshError.HostKeyChanged) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SshError.HostKeyChanged -> "The tablet's SSH host key changed. If you expected this, trust the new key in Settings."
        is SshError.Unreachable, is SshError.Timeout ->
            "${e.message}. Check that Tailscale is connected on this phone and the tablet is awake."
        is SshError -> e.message ?: "SSH error"
        is IllegalArgumentException -> "The SillyTavern user folder in Settings is invalid"
        else -> e.message ?: e.javaClass.simpleName
    }
}
