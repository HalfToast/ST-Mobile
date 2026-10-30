package dev.halftoast.stmobile.chat

import dev.halftoast.stmobile.chat.ConnectionWatch.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionWatchTest {
    private var clock = 0L
    private val watch = ConnectionWatch { clock }

    private fun vpnWasUp() = assertEquals(Decision.None, watch.onVpn(up = true, pageLive = true))

    @Test fun vpnDownWhileReadyCoversAtOnceWithoutReload() {
        vpnWasUp()
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onVpn(up = false, pageLive = true))
        assertEquals(UnreachableCase.TailscaleOff, watch.cover)
    }

    @Test fun aVpnThatWasNeverUpCoversNothing() {
        // The first report is the starting point, not a change (e.g. a server on the LAN).
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = true))
        assertNull(watch.cover)
    }

    @Test fun nothingWhileThePageIsNotLive() {
        vpnWasUp()
        assertEquals(Decision.None, watch.onVpn(up = false, pageLive = false))
        assertEquals(Decision.None, watch.onPageFailure(pageLive = false))
    }

    @Test fun vpnBackWithNoFailuresUncoversWithoutReload() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true))
        assertEquals(Decision.Uncover, watch.onProbe(verdict = null, pageLive = true))
        assertNull(watch.cover)
    }

    @Test fun vpnBackAfterAFailedRequestReloads() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        assertEquals(Decision.None, watch.onPageFailure(pageLive = true)) // counted, no probe while covered
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true))
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
        assertEquals(false, watch.failedDuringOutage)
    }

    @Test fun pageFailureWithTheServerUnreachableCoversWithTheMatchingCase() {
        vpnWasUp()
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(
            Decision.Cover(UnreachableCase.TabletNotAnswering),
            watch.onProbe(verdict = UnreachableCase.TabletNotAnswering, pageLive = true),
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
        watch.onVpn(up = false, pageLive = true)
        assertEquals(Decision.Probe, watch.onRetry())
        assertEquals(Decision.Cover(UnreachableCase.TailscaleOff), watch.onProbe(UnreachableCase.TailscaleOff, pageLive = true))
        assertEquals(UnreachableCase.TailscaleOff, watch.cover)
        // The tablet is still asleep once the VPN is back: the case follows.
        assertEquals(Decision.Cover(UnreachableCase.TabletNotAnswering), watch.onProbe(UnreachableCase.TabletNotAnswering, true))
    }

    @Test fun retryAndTheVpnComingBackTogetherReloadOnce() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        watch.onPageFailure(pageLive = true)
        assertEquals(Decision.Probe, watch.onRetry())
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true))
        assertEquals(Decision.Reload, watch.onProbe(verdict = null, pageLive = true))
        assertEquals("the second probe's answer", Decision.None, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun retryAndTicksOnlyProbeWhileCovered() {
        assertEquals(Decision.None, watch.onRetry())
        assertEquals(Decision.None, watch.onTick())
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        assertEquals(Decision.Probe, watch.onTick())
    }

    @Test fun aReloadOrLeavingTheLivePageForgetsTheOutage() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        watch.onPageFailure(pageLive = true)
        watch.onPageReset()
        assertNull(watch.cover)
        assertEquals(false, watch.failedDuringOutage)
        assertEquals(Decision.None, watch.onProbe(verdict = null, pageLive = true))
    }

    @Test fun aProbeAnswerAfterThePageWentAwayIsIgnored() {
        assertEquals(Decision.Probe, watch.onPageFailure(pageLive = true))
        assertEquals(Decision.None, watch.onProbe(UnreachableCase.TabletNotAnswering, pageLive = false))
        assertNull(watch.cover)
    }

    @Test fun verdictIsReachableWheneverThePortAnswers() {
        assertNull(probeVerdict(vpnUp = false, failure = null))
        assertNull(probeVerdict(vpnUp = true, failure = null))
        assertEquals(UnreachableCase.TailscaleOff, probeVerdict(vpnUp = false, failure = ConnectFailure.Timeout))
        assertEquals(UnreachableCase.ServerNotRunning, probeVerdict(vpnUp = true, failure = ConnectFailure.Refused))
        assertEquals(UnreachableCase.TabletNotAnswering, probeVerdict(vpnUp = true, failure = ConnectFailure.Timeout))
    }

    @Test fun aRefusedPortMeansTheProcessWasGoneSoComingBackReloads() {
        vpnWasUp()
        watch.onVpn(up = false, pageLive = true)
        assertEquals(Decision.Probe, watch.onVpn(up = true, pageLive = true))
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
            Decision.Cover(UnreachableCase.TabletNotAnswering),
            watch.onProbe(UnreachableCase.TabletNotAnswering, pageLive = true),
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
        watch.onVpn(up = false, pageLive = true)
        watch.onRetry()
        watch.onProbe(UnreachableCase.TailscaleOff, pageLive = true)
        assertEquals(false, watch.failedDuringOutage)
    }
}
