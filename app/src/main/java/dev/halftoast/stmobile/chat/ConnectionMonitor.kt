package dev.halftoast.stmobile.chat

import android.os.SystemClock
import android.util.Log
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.chat.ConnectionWatch.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Carries out [ConnectionWatch] for the live Chat: watches the phone's VPN (only for a Tailscale
 * server, re-checked whenever the page reloads, as it does for a new address) and the page's failed
 * requests, probes the server off the main thread (real ST must answer /version, see [UnreachableProbe.check]), and covers, uncovers or reloads through [holder].
 * Runs only between [start] and [stop] (the Activity's foreground): no callback or probe loop in the
 * background; [start] picks up whatever changed meanwhile. [scope] runs on the main thread.
 */
class ConnectionMonitor(
    private val holder: WebViewHolder,
    private val probe: UnreachableProbe,
    private val serverUrl: () -> String,
    private val scope: CoroutineScope,
) {
    private val watch = ConnectionWatch(SystemClock::elapsedRealtime)
    private var stopVpnWatch: (() -> Unit)? = null
    private var ticker: Job? = null
    private var probing: Job? = null
    private var deferred: Job? = null
    /** A probe was asked for while one ran: run once more after its answer. */
    private var probeAgain = false
    private var started = false

    private val _checking = MutableStateFlow(false)
    /** A probe is running (the covered screen's Retry shows it is busy). */
    val checking: StateFlow<Boolean> = _checking

    init {
        holder.onNetworkFailure = { if (started) handle(watch.onPageFailure(live())) }
        scope.launch {
            holder.state.collect {
                if (!it.coversOnConnectionLoss) {
                    watch.onPageReset()
                    stopTicker()
                }
                // A new address reloads the page: watch the VPN only if it is a Tailscale one.
                syncVpnWatch()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        syncVpnWatch()
        if (watch.cover != null) {
            startTicker()
            handle(Decision.Probe)
        } else {
            // A probe deferred before stop() never ran; run it now, or later failures stay unprobed.
            handle(watch.onDeferredProbe(live()))
        }
    }

    fun stop() {
        started = false
        syncVpnWatch()
        stopTicker()
        deferred?.cancel()
        deferred = null
        // A running probe's answer is dropped; start() probes again if the chat is covered.
        if (probing?.isActive == true) {
            probing?.cancel()
            watch.onProbeAbandoned()
        }
        probing = null
        probeAgain = false
        _checking.value = false
    }

    /** The covered screen's Retry: uncover, reload or stay, as for the automatic return. */
    fun retry() = handle(watch.onRetry())

    private fun live() = holder.state.value.coversOnConnectionLoss

    private fun tailscaleHost() = isTailscaleServer(serverUrl())

    /**
     * The VPN is watched exactly while started and the server is a Tailscale address. The first report
     * is the VPN as it is now; a change since stop() still counts, but not across another server
     * (Tailscale, then a LAN address, then Tailscale again starts afresh).
     */
    private fun syncVpnWatch() {
        val tailscale = tailscaleHost()
        if (!tailscale) watch.forgetVpn()
        val want = started && tailscale
        if (want == (stopVpnWatch != null)) return
        if (want) {
            stopVpnWatch = probe.watchVpn { up -> scope.launch { handle(watch.onVpn(up, live(), tailscaleHost())) } }
        } else {
            stopVpnWatch?.invoke()
            stopVpnWatch = null
        }
    }

    private fun handle(decision: Decision) {
        when (decision) {
            Decision.None -> Unit
            is Decision.Cover -> {
                if (holder.cover.value != decision.case) Log.i(StMobileApp.LOG_TAG, "CONN cover=${decision.case}")
                holder.cover(decision.case)
                startTicker()
            }
            Decision.Uncover -> {
                Log.i(StMobileApp.LOG_TAG, "CONN uncover reload=false")
                stopTicker()
                holder.uncover()
            }
            Decision.Reload -> {
                Log.i(StMobileApp.LOG_TAG, "CONN uncover reload=true")
                // ST asks for a reload after a failed save; Loading (with its pill) drops the cover.
                stopTicker()
                holder.reload()
            }
            Decision.Probe -> runProbe()
            is Decision.ProbeLater -> {
                deferred?.cancel()
                deferred = scope.launch {
                    delay(decision.delayMs)
                    if (started) handle(watch.onDeferredProbe(live()))
                }
            }
        }
    }

    private fun runProbe() {
        if (!started) return
        if (probing?.isActive == true) {
            probeAgain = true
            return
        }
        _checking.value = true
        probing = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val verdict = probe.check(serverUrl())
            Log.i(StMobileApp.LOG_TAG, "CONN probe=${verdict ?: "reachable"} ms=${SystemClock.elapsedRealtime() - startedAt}")
            if (!started) return@launch
            probing = null
            _checking.value = false
            handle(watch.onProbe(verdict, live()))
            if (probeAgain) {
                probeAgain = false
                // Asked for while that one ran; still wanted if the chat is covered.
                handle(watch.onRetry())
            }
        }
    }

    private fun startTicker() {
        if (!started || ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                // A probe can take about as long as a tick; one already running answers this tick too.
                if (probing?.isActive != true) handle(watch.onTick())
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        /** How often a covered chat checks whether the server is back. */
        const val TICK_MS = 5_000L
    }
}
