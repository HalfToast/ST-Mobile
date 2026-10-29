package dev.halftoast.stmobile.chat

import dev.halftoast.stmobile.panel.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnRetryGateTest {
    @Test fun retriesOnceWhenTheVpnComesUp() {
        val gate = VpnRetryGate()
        gate.start(vpnUp = false)
        assertEquals(false, gate.onVpnChange(vpnUp = false)) // a callback while still no VPN
        assertEquals(true, gate.onVpnChange(vpnUp = true))   // no VPN -> VPN up
        assertEquals(false, gate.onVpnChange(vpnUp = true))  // the same VPN reported again
    }

    @Test fun anAlreadyUpVpnNeverRetries() {
        // registerNetworkCallback reports a matching VPN at once: that is no transition.
        val gate = VpnRetryGate()
        gate.start(vpnUp = true)
        assertEquals(false, gate.onVpnChange(vpnUp = true))
    }

    @Test fun aNewWatchAfterTheRetryDoesNotLoop() {
        val gate = VpnRetryGate()
        gate.start(vpnUp = false)
        assertEquals(true, gate.onVpnChange(vpnUp = true))
        // The retry failed and the screen watches again: the immediate callback is not a new event.
        gate.start(vpnUp = false)
        assertEquals(false, gate.onVpnChange(vpnUp = false))
        // Only a real down -> up again retries.
        assertEquals(true, gate.onVpnChange(vpnUp = true))
    }

    @Test fun nothingBeforeStart() = assertEquals(false, VpnRetryGate().onVpnChange(vpnUp = true))
}

class StartOutcomeTest {
    private val before = ActionResult.Ok("Backup done")

    @Test fun showsTheFirstNewResultAfterAnAcceptedStart() {
        val watch = StartOutcomeWatch()
        watch.arm(before)
        assertNull(watch.onLastAction(before))
        val result = ActionResult.Failed("Start failed (exit 1)")
        assertEquals(result, watch.onLastAction(result))
        assertNull("shown once", watch.onLastAction(ActionResult.Ok("Server stopped")))
    }

    @Test fun nothingUnlessArmed() {
        // A press that the Control Center ignored (busy) never arms the watch.
        val watch = StartOutcomeWatch()
        assertNull(watch.onLastAction(ActionResult.Ok("Backup done")))
    }

    @Test fun armedWithNoEarlierResult() {
        val watch = StartOutcomeWatch()
        watch.arm(null)
        assertNull(watch.onLastAction(null))
        val ok = ActionResult.Ok("Start requested")
        assertEquals(ok, watch.onLastAction(ok))
    }
}
