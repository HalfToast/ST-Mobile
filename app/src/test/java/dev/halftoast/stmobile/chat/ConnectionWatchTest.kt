package dev.halftoast.stmobile.chat

import dev.halftoast.stmobile.chat.ConnectionWatch.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionWatchTest {
    private var clock = 0L
    private val watch = ConnectionWatch { clock }

    private fun vpnWasUp() = assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))

    @Test fun vpnDownWhileReadyCoversAtOnceWithoutReload() {
        vpnWasUp()
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
        assertEquals(UnreachableCase.TailscaleOff, watch.cover)
    }

    @Test fun aVpnThatWasNeverUpCoversNothing() {
        // The first report is the starting point, not a change (e.g. a server on the LAN).
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
        assertNull(watch.cover)
    }

    @Test fun anotherServerIsNeverCoveredForTheVpn() {
        // A LAN server: the VPN going down or up says nothing about it.
        assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true, tailscaleHost = false))
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = true, tailscaleHost = false))
        assertNull(watch.cover)
        // Covered for another reason: the VPN coming up doesn't probe either (the ticker does).
        watch.onPageFailure(pageLive = true)
        watch.onProbe(UnreachableCase.ServerNotAnswering, pageLive = true)
        assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true, tailscaleHost = false))
    }

    @Test fun aVpnStateFromBeforeAnotherServerIsNotAStartingPoint() {
        // What ConnectionMonitor.syncVpnWatch does: Tailscale, then a LAN address (the watch is
        // unregistered and forgets), then Tailscale again with the page live.
        vpnWasUp()
        watch.forgetVpn()
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
        assertNull(watch.cover)
        // From then on a change counts again.
        assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
    }

    @Test fun aStopAndStartKeepsTheVpnState() {
        // The monitor unregisters on stop and registers on start without forgetting: Tailscale
        // turned off while the app was in the background covers on return.
        vpnWasUp()
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
    }

    @Test fun aReportForAnotherServerForgetsTheVpnState() {
        vpnWasUp()
        assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true, tailscaleHost = false))
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = true, tailscaleHost = true))
        assertNull(watch.cover)
    }

    @Test fun nothingWhileThePageIsNotLive() {
        vpnWasUp()
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = false, tailscaleHost = true))
        assertEquals(Decision.None, watch.onPageFailure(pageLive = false))
    }

    @Test fun vpnBackWithNoFailuresUncoversWithoutReload() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))
        assertEquals(Decision.Uncover, watch.onProbe(verdict = null, pageLive = true))
        assertNull(watch.cover)
    }

    @Test fun vpnBackAfterAFailedRequestReloads() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        assertEquals(Decision.None, watch.onPageFailure(pageLive = true)) // counted, no probe while covered
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
        assertEquals(false, watch.failedDuringOutage)
    }

    @Test fun pageFailureWithTheServerUnreachableCoversWithTheMatchingCase() {
        vpnWasUp()
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(
            Decision.Cover(UnreachableCase.ServerNotAnswering),
            watch.onProbe(verdict = UnreachableCase.ServerNotAnswering, pageLive = true),
        )
        // The request that failed counts: coming back reloads.
        assertEquals(true, watch.failedDuringOutage)
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun pageFailureWithTheServerFineChangesNothing() {
        vpnWasUp()
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(Decision.None, watch.onProbe(verdict = null, pageLive = true))
        assertNull(watch.cover)
        assertEquals(false, watch.failedDuringOutage)
    }

    @Test fun pageFailuresProbeAtMostEveryThreeSeconds() {
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        watch.onProbe(verdict = null, pageLive = true)
        clock = 2_999
        assertEquals("deferred to the end of the gap", Decision.ProbeLater(1), watch.onPageFailure(pageLive = true))
        clock = 3_000
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals("the deferred one is then moot", Decision.None, watch.onDeferredProbe(pageLive = true))
    }

    @Test fun retryWithTheServerStillDownStaysCovered() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        assertEquals(Decision.Probe, watch.onRetry())
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onProbe(UnreachableCase.TailscaleOff, pageLive = true))
        assertEquals(UnreachableCase.TailscaleOff, watch.cover)
        // The server is still asleep once the VPN is back: the case follows.
        assertEquals(Decision.Cover(UnreachableCase.ServerNotAnswering), watch.onProbe(UnreachableCase.ServerNotAnswering, true))
    }

    @Test fun retryAndTheVpnComingBackTogetherReloadOnce() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        watch.onPageFailure(pageLive = true)
        assertEquals(Decision.Probe, watch.onRetry())
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
        assertEquals("the second probe's answer", Decision.None, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun retryAndTicksOnlyProbeWhileCovered() {
        assertEquals(Decision.None, watch.onRetry())
        assertEquals(Decision.None, watch.onTick())
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        assertEquals(Decision.Probe, watch.onTick())
    }

    @Test fun aReloadOrLeavingTheLivePageForgetsTheOutage() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        watch.onPageFailure(pageLive = true)
        watch.onPageReset()
        assertNull(watch.cover)
        assertEquals(false, watch.failedDuringOutage)
        assertEquals(Decision.None, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun aProbeAnswerAfterThePageWentAwayIsIgnored() {
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(Decision.None, watch.onProbe(UnreachableCase.ServerNotAnswering, pageLive = false))
        assertNull(watch.cover)
    }


    @Test fun aRefusedPortMeansTheProcessWasGoneSoComingBackReloads() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true, tailscaleHost = true))
        assertEquals(Decision.Cover(UnreachableCase.ServerNotRunning), watch.onProbe(UnreachableCase.ServerNotRunning, true))
        // Restarted (from the cover or the Control Center): the page's session belongs to the old process.
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun aDebouncedFailureIsProbedLaterNotForgotten() {
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(Decision.None, watch.onProbe(verdict = null, pageLive = true)) // a one-off
        clock = 1_000
        assertEquals(Decision.ProbeLater(2_000), watch.onPageFailure(pageLive = true))
        clock = 1_500
        assertEquals("one deferred probe at a time", Decision.None, watch.onPageFailure(pageLive = true))
        clock = 3_000
        assertEquals(Decision.Probe, watch.onDeferredProbe(pageLive = true))
        assertEquals(
            Decision.Cover(UnreachableCase.ServerNotAnswering),
            watch.onProbe(UnreachableCase.ServerNotAnswering, pageLive = true),
        )
        assertEquals("the deferred failure counts", true, watch.failedDuringOutage)
    }

    @Test fun aDeferredProbeAfterACoverOrResetDoesNothing() {
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        watch.onProbe(verdict = null, pageLive = true)
        assertEquals(Decision.ProbeLater(3_000), watch.onPageFailure(pageLive = true))
        watch.onPageReset()
        assertEquals(Decision.None, watch.onDeferredProbe(pageLive = true))
        assertEquals(Decision.None, watch.onDeferredProbe(pageLive = false))
    }

    @Test fun anAbandonedProbeIsNotMistakenForAFailureProbeLater() {
        vpnWasUp()
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        watch.onProbeAbandoned() // the app went to the background mid-probe
        watch.onVpn(up = false, pageLive = true, tailscaleHost = true)
        watch.onRetry()
        watch.onProbe(UnreachableCase.TailscaleOff, pageLive = true)
        assertEquals(false, watch.failedDuringOutage)
    }
}
