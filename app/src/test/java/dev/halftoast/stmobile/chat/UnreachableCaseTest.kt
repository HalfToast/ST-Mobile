package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UnreachableCaseTest {
    private val silent = ServerAnswer.None
    private val SERVER = "http://100.101.102.103:8000/"

    @Test fun noVpnMeansTailscaleIsOffEvenIfTcpConnects() {
        // A carrier's CGNAT accepts TCP to any 100.x address: a bare connect proves nothing.
        (ConnectFailure.entries + listOf(null)).forEach {
            assertEquals("$it", UnreachableCase.TailscaleOff, classifyUnreachable(vpnUp = false, answer = silent, connect = it, tailscaleHost = true))
        }
    }

    @Test fun refusedMeansTheServerIsNotRunning() = assertEquals(
        UnreachableCase.ServerNotRunning,
        classifyUnreachable(vpnUp = true, answer = silent, connect = ConnectFailure.Refused, tailscaleHost = true),
    )

    @Test fun anythingElseMeansTheServerIsNotAnswering() {
        listOf(ConnectFailure.Timeout, ConnectFailure.Unreachable, ConnectFailure.Other, null).forEach {
            assertEquals("$it", UnreachableCase.ServerNotAnswering, classifyUnreachable(vpnUp = true, answer = silent, connect = it, tailscaleHost = true))
        }
    }

    @Test fun onlyRealSillyTavernAnsweringCounts() {
        // TCP connects, but what answers /version isn't ST: not reachable.
        assertEquals(UnreachableCase.ServerNotAnswering, connectionVerdict(vpnUp = true, answer = ServerAnswer.None, connect = null, tailscaleHost = true))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(vpnUp = false, answer = ServerAnswer.None, connect = null, tailscaleHost = true))
        // ST itself answered: reachable, VPN or not (a server on the LAN).
        assertEquals(null, connectionVerdict(vpnUp = true, answer = ServerAnswer.SillyTavern, connect = null, tailscaleHost = true))
        assertEquals(null, connectionVerdict(vpnUp = false, answer = ServerAnswer.SillyTavern, connect = null, tailscaleHost = true))
        // ST's own login redirect counts only through the VPN, like a 401/403.
        assertEquals(null, connectionVerdict(vpnUp = true, answer = ServerAnswer.LoginRedirect, connect = null, tailscaleHost = true))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(vpnUp = false, answer = ServerAnswer.LoginRedirect, connect = null, tailscaleHost = true))
        // A 401/403 counts only through the VPN (it is ST's auth or whitelist, not a carrier box).
        assertEquals(null, connectionVerdict(vpnUp = true, answer = ServerAnswer.AuthRequired, connect = null, tailscaleHost = true))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(vpnUp = false, answer = ServerAnswer.AuthRequired, connect = null, tailscaleHost = true))
        // The Unreachable screen keeps the page's own reason when ST answered.
        assertEquals(UnreachableCase.Other, classifyUnreachable(vpnUp = true, answer = ServerAnswer.AuthRequired, connect = null, tailscaleHost = true))
    }

    @Test fun tailscaleAddresses() {
        listOf(
            "100.64.0.1", "100.127.255.254", "100.101.102.103", "[fd7a:115c:a1e0::1]",
            "fd7a:115c:a1e0:ab12:4843:cd96:6258:b240", "mypc.tail1234.ts.net", "MYPC.TAIL1234.TS.NET",
        ).forEach { assertTrue(it, isTailscaleHost(it)) }
        listOf(
            "100.63.255.255", "100.128.0.1", "192.168.1.20", "fd00::1", "ts.net.example.com", "localhost",
            "fd7a:115c:a1e1::1", "100.64.0", "100.64.0.1.5", "100.64.0.256", "ts.net", "", "[fd7a:115c:a1e0::zz]",
        ).forEach { assertFalse(it, isTailscaleHost(it)) }
        assertTrue(isTailscaleServer("http://100.101.102.103:8000/"))
        assertTrue(isTailscaleServer("http://[fd7a:115c:a1e0::1]:8000"))
        assertFalse(isTailscaleServer("http://192.168.1.20:8000/"))
        assertFalse(isTailscaleServer("not a url"))
    }

    @Test fun anotherServerNeverBlamesTailscale() {
        // A LAN server: the phone's VPN says nothing about it.
        listOf(true, false).forEach { vpn ->
            ServerAnswer.entries.forEach { answer ->
                (ConnectFailure.entries + listOf(null)).forEach { connect ->
                    assertNotEquals(
                        "$vpn $answer $connect",
                        UnreachableCase.TailscaleOff,
                        connectionVerdict(vpn, answer, connect, tailscaleHost = false),
                    )
                }
            }
        }
        // ST's auth or its own login redirect is ST answering, VPN or not.
        assertEquals(null, connectionVerdict(vpnUp = false, answer = ServerAnswer.AuthRequired, connect = null, tailscaleHost = false))
        assertEquals(null, connectionVerdict(vpnUp = false, answer = ServerAnswer.LoginRedirect, connect = null, tailscaleHost = false))
        assertEquals(null, connectionVerdict(vpnUp = false, answer = ServerAnswer.SillyTavern, connect = null, tailscaleHost = false))
        listOf(true, false).forEach { vpn ->
            assertEquals(
                UnreachableCase.ServerNotRunning,
                connectionVerdict(vpn, silent, ConnectFailure.Refused, tailscaleHost = false),
            )
            assertEquals(
                UnreachableCase.ServerNotAnswering,
                connectionVerdict(vpn, silent, ConnectFailure.Timeout, tailscaleHost = false),
            )
        }
    }

    @Test fun whatAnsweredVersion() {
        val st = """{"agent":"SillyTavern:1.19.0:Cohee#1207","pkgVersion":"1.19.0","gitRevision":"ad29cbda6","gitBranch":"staging"}"""
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(200, null, st, SERVER))
        assertEquals(ServerAnswer.SillyTavern, serverAnswerOf(200, null, """{"pkgVersion":"1.12.0"}""", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, "<!doctype html><html>Welcome to your carrier</html>", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, "", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, null, SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, "\u0000garbage{", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, """{"status":"ok"}""", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(200, null, """{"pkgVersion":""}""", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(500, null, st, SERVER))
        // No session yet: ST sends the page to its own login, on its own origin.
        assertEquals(ServerAnswer.LoginRedirect, serverAnswerOf(302, "/login", null, SERVER))
        assertEquals(ServerAnswer.LoginRedirect, serverAnswerOf(302, "http://100.101.102.103:8000/login?next=/", null, SERVER))
        assertEquals(ServerAnswer.LoginRedirect, serverAnswerOf(302, "HTTP://100.101.102.103:8000/login", null, SERVER))
        // A captive portal's own login page is not ST's.
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "http://portal.carrier.example/login", null, SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "https://100.101.102.103:8000/login", null, SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "http://100.101.102.103:9000/login", null, SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "//portal.carrier.example/login", null, SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(302, "http://portal.carrier.example/", null, SERVER))
        assertEquals(ServerAnswer.LoginRedirect, serverAnswerOf(302, "http://mypc.tail1234.ts.net/login", null, "http://mypc.tail1234.ts.net"))
        // End to end: a relative /login counts as ST through the VPN only; a portal's /login never.
        assertEquals(null, connectionVerdict(true, serverAnswerOf(302, "/login", null, SERVER), null, tailscaleHost = true))
        assertEquals(UnreachableCase.TailscaleOff, connectionVerdict(false, serverAnswerOf(302, "/login", null, SERVER), null, tailscaleHost = true))
        assertEquals(
            UnreachableCase.ServerNotAnswering,
            connectionVerdict(true, serverAnswerOf(302, "http://portal.carrier.example/login", null, SERVER), null, tailscaleHost = true),
        )
        assertEquals(ServerAnswer.AuthRequired, serverAnswerOf(401, null, null, SERVER))
        assertEquals(ServerAnswer.AuthRequired, serverAnswerOf(403, null, "Forbidden", SERVER))
        assertEquals(ServerAnswer.None, serverAnswerOf(-1, null, null, SERVER))
    }

    @Test fun failuresFromSocketExceptions() {
        assertEquals(ConnectFailure.Refused, connectFailureOf(ConnectException("failed to connect to /100.1.2.3 (port 8000) after 2000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals(ConnectFailure.Refused, connectFailureOf(ConnectException("Connection refused")))
        assertEquals(ConnectFailure.Timeout, connectFailureOf(SocketTimeoutException("failed to connect to /100.1.2.3 (port 8000) after 2000ms")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(NoRouteToHostException("No route to host")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(ConnectException("failed to connect: connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(ConnectFailure.Unreachable, connectFailureOf(ConnectException("connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(ConnectFailure.Other, connectFailureOf(UnknownHostException("st-server")))
        assertEquals(ConnectFailure.Other, connectFailureOf(ConnectException(null)))
        assertEquals(null, connectFailureOf(null))
    }

    @Test fun hostAndPortOfTheServerUrl() {
        assertEquals("100.101.102.103" to 8000, serverEndpoint("http://100.101.102.103:8000/"))
        assertEquals("mypc.tail1234.ts.net" to 80, serverEndpoint("http://mypc.tail1234.ts.net"))
        assertEquals("mypc.tail1234.ts.net" to 443, serverEndpoint("https://mypc.tail1234.ts.net/st/"))
        assertEquals(null, serverEndpoint("not a url"))
    }
}
