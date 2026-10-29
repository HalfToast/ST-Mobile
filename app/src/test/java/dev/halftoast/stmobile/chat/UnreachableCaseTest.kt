package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UnreachableCaseTest {
    @Test fun noVpnMeansTailscaleIsOff() {
        ConnectFailure.entries.forEach {
            assertEquals(UnreachableCase.TailscaleOff, classifyUnreachable(vpnUp = false, failure = it))
        }
        assertEquals(UnreachableCase.TailscaleOff, classifyUnreachable(vpnUp = false, failure = null))
    }

    @Test fun refusedMeansTheServerIsNotRunning() =
        assertEquals(UnreachableCase.ServerNotRunning, classifyUnreachable(vpnUp = true, failure = ConnectFailure.Refused))

    @Test fun anythingElseMeansTheTabletIsNotAnswering() {
        listOf(ConnectFailure.Timeout, ConnectFailure.Unreachable, ConnectFailure.Other).forEach {
            assertEquals(it.name, UnreachableCase.TabletNotAnswering, classifyUnreachable(vpnUp = true, failure = it))
        }
    }

    @Test fun aPortThatAnswersIsNoneOfThese() =
        assertEquals(UnreachableCase.Other, classifyUnreachable(vpnUp = true, failure = null))

    @Test fun failuresFromSocketExceptions() {
        assertEquals(ConnectFailure.Refused, connectFailureOf(ConnectException("failed to connect to /100.1.2.3 (port 8000) after 2000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals(ConnectFailure.Refused, connectFailureOf(ConnectException("Connection refused")))
        assertEquals(ConnectFailure.Timeout, connectFailureOf(SocketTimeoutException("failed to connect to /100.1.2.3 (port 8000) after 2000ms")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(NoRouteToHostException("No route to host")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(ConnectException("failed to connect: connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(ConnectException("connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(ConnectFailure.Other, connectFailureOf(UnknownHostException("tablet")))
        assertEquals(ConnectFailure.Other, connectFailureOf(ConnectException(null)))
        assertEquals(null, connectFailureOf(null))
    }

    @Test fun hostAndPortOfTheServerUrl() {
        assertEquals("100.101.102.103" to 8000, serverEndpoint("http://100.101.102.103:8000/"))
        assertEquals("tablet.ts.net" to 80, serverEndpoint("http://tablet.ts.net"))
        assertEquals("tablet.ts.net" to 443, serverEndpoint("https://tablet.ts.net/st/"))
        assertEquals(null, serverEndpoint("not a url"))
    }
}
