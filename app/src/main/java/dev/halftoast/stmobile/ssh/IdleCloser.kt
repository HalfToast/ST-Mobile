package dev.halftoast.stmobile.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Counts open SSH sessions (exec calls and streams) and closes the shared connection once nothing has
 * used it for [idleMs], or right away when the app goes to the background with nothing running.
 * Never closes while a session is open: an action or a log stream keeps the connection.
 *
 * [closeIfIdle] receives a check to run under the connection lock, so a session that starts while the
 * close is being decided is never cut off.
 */
class IdleCloser(
    private val scope: CoroutineScope,
    private val idleMs: Long,
    private val closeIfIdle: suspend (isIdle: () -> Boolean) -> Unit,
) {
    private var active = 0
    private var timer: Job? = null

    val activeSessions: Int @Synchronized get() = active

    /** A session is about to open. Cancels a pending idle close. */
    @Synchronized fun begin() {
        active++
        timer?.cancel()
        timer = null
    }

    /** A session closed. Starts the idle countdown when it was the last one. */
    @Synchronized fun end() {
        active = (active - 1).coerceAtLeast(0)
        if (active == 0) arm()
    }

    /** The app went to the background: close now unless something is running. */
    fun onBackground() {
        synchronized(this) {
            if (active > 0) return
            timer?.cancel()
            timer = null
        }
        scope.launch { closeIfIdle(::isIdle) }
    }

    @Synchronized private fun isIdle() = active == 0

    private fun arm() {
        timer?.cancel()
        timer = scope.launch {
            delay(idleMs)
            closeIfIdle(::isIdle)
        }
    }
}
