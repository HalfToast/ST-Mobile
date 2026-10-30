package dev.halftoast.stmobile.chat

/**
 * The connection dropping under a loaded chat (Tailscale off, tablet asleep, server stopped). Pure
 * decisions; [ConnectionMonitor] feeds it events and carries out what it returns.
 *
 * - The phone's VPN going down while the page is live covers it at once (TailscaleOff).
 * - A network failure of one of ST's own requests probes the server (at most every
 *   [FAILURE_PROBE_GAP_MS]; a failure within the gap is probed when it ends); a probe that can't reach
 *   it covers with the matching case, a probe that can is a one-off and changes nothing.
 * - While covered: the VPN coming back, a Retry or a periodic tick probes; once the server answers,
 *   the cover goes. If any of ST's requests failed during the outage, or ST's port was refused (its
 *   process was gone), the page reloads (as ST asks); otherwise it is just uncovered, exactly as it was.
 * - A reload, or the page leaving the live states, forgets the outage ([onPageReset]).
 *
 * "Live" is a loaded server page: Ready, Stalled or LoginRequired.
 */
class ConnectionWatch(private val now: () -> Long) {
    sealed interface Decision {
        data object None : Decision
        data class Cover(val case: UnreachableCase) : Decision
        data object Uncover : Decision
        data object Reload : Decision
        /** Run the probe and report it with [onProbe]. */
        data object Probe : Decision
        /** Call [onDeferredProbe] in [delayMs]: a failure came within the probe gap. */
        data class ProbeLater(val delayMs: Long) : Decision
    }

    /** What covers the chat now, or null. */
    var cover: UnreachableCase? = null
        private set

    /** One of ST's requests failed since the outage began: coming back reloads. */
    var failedDuringOutage = false
        private set

    private var vpnUp: Boolean? = null
    private var lastFailureProbeAt: Long? = null
    private var probingForFailure = false
    private var deferredProbePending = false

    /** The VPN was reported [up] or down. The first report is only the starting point. */
    fun onVpn(up: Boolean, pageLive: Boolean): Decision {
        val before = vpnUp
        vpnUp = up
        return when {
            !up && before == true && pageLive -> coverWith(UnreachableCase.TailscaleOff)
            up && before == false && cover != null -> Decision.Probe
            else -> Decision.None
        }
    }

    /** The page reported a network-level failure of one of ST's requests. */
    fun onPageFailure(pageLive: Boolean): Decision {
        if (!pageLive) return Decision.None
        if (cover != null) {
            failedDuringOutage = true
            return Decision.None
        }
        val last = lastFailureProbeAt
        if (last != null && now() - last < FAILURE_PROBE_GAP_MS) {
            // Too soon after the last probe: probe once the gap is over, so the real start of an
            // outage right after a one-off failure is not missed.
            if (deferredProbePending) return Decision.None
            deferredProbePending = true
            return Decision.ProbeLater(last + FAILURE_PROBE_GAP_MS - now())
        }
        return failureProbe()
    }

    /** The probe [Decision.ProbeLater] asked for is due. */
    fun onDeferredProbe(pageLive: Boolean): Decision {
        if (!deferredProbePending) return Decision.None
        deferredProbePending = false
        if (!pageLive) return Decision.None
        if (cover != null) {
            failedDuringOutage = true
            return Decision.None
        }
        return failureProbe()
    }

    /** A probe was dropped without an answer (the app went to the background). */
    fun onProbeAbandoned() {
        probingForFailure = false
    }

    private fun failureProbe(): Decision {
        deferredProbePending = false
        lastFailureProbeAt = now()
        probingForFailure = true
        return Decision.Probe
    }

    /** A probe finished: [verdict] is null if the server answered, else why it didn't. */
    fun onProbe(verdict: UnreachableCase?, pageLive: Boolean): Decision {
        val forFailure = probingForFailure
        probingForFailure = false
        if (!pageLive) return Decision.None
        if (verdict == null) {
            if (cover == null) return Decision.None
            val reload = failedDuringOutage
            cover = null
            failedDuringOutage = false
            return if (reload) Decision.Reload else Decision.Uncover
        }
        // A refused port means ST's process was gone: the page's session belongs to the old one, so
        // coming back reloads even if none of its requests failed.
        if (forFailure || verdict == UnreachableCase.ServerNotRunning) failedDuringOutage = true
        return coverWith(verdict)
    }

    /** The covered screen's Retry. */
    fun onRetry(): Decision = if (cover != null) Decision.Probe else Decision.None

    /** The periodic check while covered. */
    fun onTick(): Decision = if (cover != null) Decision.Probe else Decision.None

    /** The page reloaded or stopped being live: whatever covered it no longer applies. */
    fun onPageReset() {
        cover = null
        failedDuringOutage = false
        probingForFailure = false
        deferredProbePending = false
    }

    private fun coverWith(case: UnreachableCase): Decision {
        cover = case
        return Decision.Cover(case)
    }

    companion object {
        const val FAILURE_PROBE_GAP_MS = 3_000L
    }
}
