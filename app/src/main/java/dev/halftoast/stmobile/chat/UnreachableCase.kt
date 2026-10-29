package dev.halftoast.stmobile.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.halftoast.stmobile.panel.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/** Why Chat can't reach SillyTavern, as far as the phone can tell. Picks the Unreachable screen's words and buttons. */
enum class UnreachableCase {
    /** No VPN on the phone: Tailscale (HalfToast's VPN) is off. */
    TailscaleOff,
    /** The tablet answered but refused ST's port: ST isn't running. */
    ServerNotRunning,
    /** No answer at all with the VPN up: asleep, or signed out of Tailscale. */
    TabletNotAnswering,
    /** ST's port answers; the page failed for another reason (shown as the plain reason). */
    Other,
}

/** How a TCP connect to ST's port failed. */
enum class ConnectFailure { Refused, Timeout, Unreachable, Other }

/** [failure] is null when the connect succeeded. */
fun classifyUnreachable(vpnUp: Boolean, failure: ConnectFailure?): UnreachableCase = when {
    !vpnUp -> UnreachableCase.TailscaleOff
    failure == null -> UnreachableCase.Other
    failure == ConnectFailure.Refused -> UnreachableCase.ServerNotRunning
    else -> UnreachableCase.TabletNotAnswering
}

/** Android reports a refused connect or a missing route as a ConnectException with the errno in its message. */
fun connectFailureOf(e: Throwable?): ConnectFailure? {
    if (e == null) return null
    val message = e.message.orEmpty()
    return when {
        e is SocketTimeoutException -> ConnectFailure.Timeout
        e is NoRouteToHostException || "ENETUNREACH" in message || "EHOSTUNREACH" in message -> ConnectFailure.Unreachable
        e is ConnectException && ("ECONNREFUSED" in message || "Connection refused" in message) -> ConnectFailure.Refused
        else -> ConnectFailure.Other
    }
}

/** Host and port of [serverUrl] (scheme default port if none), or null if it isn't a URL with a host. */
fun serverEndpoint(serverUrl: String): Pair<String, Int>? = try {
    val uri = URI(serverUrl.trim())
    val host = uri.host ?: return null
    val port = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    host to port
} catch (_: Exception) {
    null
}

/**
 * Retry at most once per change from "no VPN" to "VPN up". The VPN callback also reports the state it
 * finds at registration, and the screen registers again after each failed retry, so those reports
 * must not count as the VPN coming up.
 */
class VpnRetryGate {
    private var lastUp: Boolean? = null

    /** Watching starts with the VPN [vpnUp] as found now. */
    fun start(vpnUp: Boolean) {
        lastUp = vpnUp
    }

    /** The VPN state was reported; true exactly when this is the no-VPN to VPN-up change. */
    fun onVpnChange(vpnUp: Boolean): Boolean {
        val retry = lastUp == false && vpnUp
        if (lastUp != null) lastUp = vpnUp
        return retry
    }
}

/**
 * The outcome of a Start pressed on the Unreachable screen: armed only when the Control Center took
 * the request, then the first new last-action result is Start's, shown once.
 */
class StartOutcomeWatch {
    private var armed = false
    private var before: ActionResult? = null

    /** Start was accepted while [lastAction] was the latest result. */
    fun arm(lastAction: ActionResult?) {
        armed = true
        before = lastAction
    }

    /** [lastAction] is now the latest result; returns it if it is Start's outcome. */
    fun onLastAction(lastAction: ActionResult?): ActionResult? {
        if (!armed || lastAction == null || lastAction === before) return null
        armed = false
        before = null
        return lastAction
    }
}

/** The phone-side probes behind [classifyUnreachable]. Logs nothing (addresses are private). */
class UnreachableProbe(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** A VPN carries this app's traffic (Tailscale's network becomes the app's active network). */
    fun vpnUp(): Boolean = try {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    /** Off the main thread: the VPN check, then a ~2 s TCP connect to ST's host and port. */
    suspend fun classify(serverUrl: String): UnreachableCase = withContext(Dispatchers.IO) {
        if (!vpnUp()) return@withContext UnreachableCase.TailscaleOff
        val (host, port) = serverEndpoint(serverUrl) ?: return@withContext UnreachableCase.Other
        val failure = try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            null
        } catch (e: Exception) {
            connectFailureOf(e)
        }
        classifyUnreachable(vpnUp = true, failure = failure)
    }

    /**
     * Reports, on a ConnectivityManager thread, whether this app's default network is a VPN: once at
     * registration, then on every change. Returns the function that unregisters it; call it once.
     */
    fun watchVpn(onVpnState: (vpnUp: Boolean) -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                onVpnState(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))

            override fun onLost(network: Network) = onVpnState(false)
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            return {}
        }
        return { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
