package dev.halftoast.stmobile.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
     * Calls [onVpnUp] (on a ConnectivityManager thread) when a VPN network becomes available.
     * Returns the function that unregisters it; call it exactly once.
     */
    fun watchVpn(onVpnUp: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onVpnUp()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            return {}
        }
        return { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
