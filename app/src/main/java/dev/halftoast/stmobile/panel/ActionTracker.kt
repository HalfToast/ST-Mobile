package dev.halftoast.stmobile.panel

import dev.halftoast.stmobile.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs Control Center commands that change the server in an app-wide scope, so leaving the
 * screen, finishing the Activity or clearing the ViewModel never cancels them; a cancelled exec would
 * close the channel mid-way. Remembers the Panel action in flight so a new [PanelViewModel] can show
 * it as busy and pick up its result.
 */
class ActionTracker(private val scope: CoroutineScope) {
    class InFlight(val action: PanelAction, val result: Deferred<ExecResult>)

    @Volatile private var current: InFlight? = null

    /** The Panel action still running, if any. */
    fun inFlight(): InFlight? = current?.takeIf { it.result.isActive }

    /** Starts [block] for [action] in the app scope; the caller awaits [InFlight.result]. */
    fun start(action: PanelAction, block: suspend () -> ExecResult): InFlight {
        val flight = InFlight(action, scope.async { block() })
        current = flight
        flight.result.invokeOnCompletion { synchronized(this) { if (current === flight) current = null } }
        return flight
    }

    /** A mutating command that isn't a Panel action (whitelist edits): app-scoped, not tracked. */
    suspend fun runDetached(block: suspend () -> ExecResult): ExecResult = scope.async { block() }.await()
}
