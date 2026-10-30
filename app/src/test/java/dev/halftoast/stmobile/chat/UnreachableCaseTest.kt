package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UnreachableCaseTest {
    private val silent = ServerAnswer.None

    @Test fun noVpnMeansTailscaleIsOffEvenIfTcpConnects() {
        // A carrier's CGNAT accepts TCP to any 100.x address: a bare connect proves nothing.
        (ConnectFailure.entries + listOf(null)).forEach {
            assertEquals("$it", UnreachableCase.TailscaleOff, classifyUnreachable(vpnUp = false, answer = silent, connect = it))
        }
    }

    @Test fun refusedMeansTheServerIsNotRunning() = assertEquals(
        UnreachableCase.ServerNotRunning,
        classifyUnreachable(vpnUp = true, answer = silent, connect = ConnectFailure.Refused),
    )

    @Test fun anythingElseMeansTheTabletIsNotAnswering() {
        listOf(ConnectFailure.Timeout, ConnectFailure.Unreachable, ConnectFailure.Other, null).forEach {
            assertEquals("$it", UnreachableCase.TabletNotAnswering, classifyUnreachable(vpnUp = true, answer = silent, connect = it))
        }
    }

    @Test fun onlyRealSillyTavernAnsweringCounts() {
        // TCP connects, but what answers /version isn't ST: not reachable.
        assertEquals(UnreachableCase.TabletNotAnswering, connectionVerdict(vpnUp = true, answer = ServerAnswer.None, connect = null))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(vpnUp = false, answer = ServerAnswer.None, connect = null))
        // ST itself answered: reachable, VPN or not (a server on the LAN).
        assertEquals(null, connectionVerdict(vpnUp = true, answer = ServerAnswer.SillyTavern, connect = null))
        assertEquals(null, connectionVerdict(vpnUp = false, answer = ServerAnswer.SillyTavern, connect = null))
        // A 401/403 counts only through the VPN (it is ST's auth or whitelist, not a carrier box).
        assertEquals(null, connectionVerdict(vpnUp = true, answer = ServerAnswer.AuthRequired, connect = null))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(vpnUp = false, answer = ServerAnswer.AuthRequired, connect = null))
        // The Unreachable screen keeps the page's own reason when ST answered.
        assertEquals(UnreachableCase.Other, classifyUnreachable(vpnUp = true, answer = ServerAnswer.AuthRequired, connect = null))
    }

    @Test fun whatAnsweredVersion() {
        val st = """{"agent":"SillyTavern:1.19.0:Cohee#1207","pkgVersion":"1.19.0","gitRevision":"ad29cbda6","gitBranch":"staging"}"""
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(200, null, st))
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(200, null, """{"pkgVersion":"1.12.0"}"""))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, "<!doctype html><html>Welcome to your carrier</html>"))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, ""))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, null))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, "\u0000garbage{"))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, """{"status":"ok"}"""))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, """{"pkgVersion":""}"""))
        assertEquals(ServerAnswer.None, serverAnswerOf(500, null, st))
        // No session yet: ST sends the page to its login.
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(302, "/login", null))
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(302, "http://100.101.102.103:8000/login?next=/", null))
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "http://portal.carrier.example/", null))
        assertEquals(ServerAnswer.AuthRequired, serverAnswerOf(401, null, null))
        assertEquals(ServerAnswer.AuthRequired, serverAnswerOf(403, null, "Forbidden"))
        assertEquals(ServerAnswer.None, serverAnswerOf(-1, null, null))
    }

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
