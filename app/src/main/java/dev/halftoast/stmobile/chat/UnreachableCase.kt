package dev.halftoast.stmobile.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.halftoast.stmobile.panel.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.CookieManager
import dev.halftoast.stmobile.stats.readAtMost
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** Why Chat can't reach SillyTavern, as far as the phone can tell. Picks the Unreachable screen's words and buttons. */
enum class UnreachableCase {
    /** No VPN on the phone: Tailscale (HalfToast's VPN) is off. */
    TailscaleOff,
    /** The tablet answered but refused ST's port: ST isn't running. */
    ServerNotRunning,
    /** No answer at all with the VPN up: asleep, or signed out of Tailscale. */
    TabletNotAnswering,
    /** SillyTavern itself answers; the page failed for another reason (shown as the plain reason). */
    Other,
}

/** How a TCP connect to ST's port failed. */
enum class ConnectFailure { Refused, Timeout, Unreachable, Other }

/**
 * What answered `GET <server>/version`. A bare TCP connect proves nothing: a carrier's CGNAT accepts
 * connects to any 100.x address with Tailscale off.
 */
enum class ServerAnswer {
    /** SillyTavern: its version JSON. */
    SillyTavern,
    /** A redirect to /login on the server's own origin: ST without a session (trusted only through the VPN). */
    LoginRedirect,
    /** 401/403: ST's auth or whitelist (trusted only through the VPN). */
    AuthRequired,
    /** No answer, or one that isn't ST's (timeout, HTML, garbage, another status). */
    None,
}

/**
 * Reads [serverUrl]'s `/version` response: status [code], its `Location` header and (for a 200) its
 * [body]. A redirect counts only to `/login` on the server's own scheme, host and port: a captive
 * portal's login page is not ST's.
 */
fun serverAnswerOf(code: Int, location: String?, body: String?, serverUrl: String): ServerAnswer = when {
    code == 200 && body != null && looksLikeStVersion(body) -> ServerAnswer.SillyTavern
    code in 300..399 && location != null && isOwnLogin(location, serverUrl) -> ServerAnswer.LoginRedirect
    code == 401 || code == 403 -> ServerAnswer.AuthRequired
    else -> ServerAnswer.None
}

/** ST's `/version` JSON: an object with a non-blank `pkgVersion` or `gitRevision` (what [parseCacheNamespace] reads). */
fun looksLikeStVersion(body: String): Boolean = try {
    val o = org.json.JSONObject(body)
    listOf("pkgVersion", "gitRevision").any { o.optString(it, "").let { v -> v.isNotBlank() && v != "null" } }
} catch (_: Exception) {
    false
}

/** [location], resolved against [serverUrl], is `/login…` on the same scheme, host and port. */
private fun isOwnLogin(location: String, serverUrl: String): Boolean = try {
    val server = URI(serverUrl.trim())
    val target = server.resolve(location.trim())
    fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    target.scheme.equals(server.scheme, ignoreCase = true) &&
        target.host != null && target.host.equals(server.host, ignoreCase = true) &&
        port(target) == port(server) &&
        (target.path ?: "").startsWith("/login")
} catch (_: Exception) {
    false
}

/**
 * Whether ST can be reached, from a probe: null when SillyTavern itself answered (its version JSON;
 * its own login redirect or a 401/403 only with the VPN up), else why not.
 * [connect] (a TCP connect, null if it connected or wasn't tried) only tells a refused port (ST not
 * running) from silence; it never proves reachability.
 */
fun connectionVerdict(vpnUp: Boolean, answer: ServerAnswer, connect: ConnectFailure?): UnreachableCase? = when {
    answer == ServerAnswer.SillyTavern -> null
    (answer == ServerAnswer.AuthRequired || answer == ServerAnswer.LoginRedirect) && vpnUp -> null
    !vpnUp -> UnreachableCase.TailscaleOff
    connect == ConnectFailure.Refused -> UnreachableCase.ServerNotRunning
    else -> UnreachableCase.TabletNotAnswering
}

/** The Unreachable screen's case: as [connectionVerdict], with [UnreachableCase.Other] when ST answered. */
fun classifyUnreachable(vpnUp: Boolean, answer: ServerAnswer, connect: ConnectFailure?): UnreachableCase =
    connectionVerdict(vpnUp, answer, connect) ?: UnreachableCase.Other

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

/** The phone-side probes behind [connectionVerdict]. Logs nothing (addresses are private). */
class UnreachableProbe(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** A VPN carries this app's traffic (Tailscale's network becomes the app's active network). */
    fun vpnUp(): Boolean = try {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    /** The Unreachable screen's case for a failed load; see [check]. */
    suspend fun classify(serverUrl: String): UnreachableCase = check(serverUrl) ?: UnreachableCase.Other

    /**
     * Off the main thread: asks ST's `/version` (with the WebView's session cookie, ~2.5 s connect and
     * read). Null when SillyTavern itself answered; otherwise the VPN check and, with the VPN up, a
     * ~2 s TCP connect to tell a refused port from silence ([connectionVerdict]). A URL without a host
     * counts as answering (nothing to say).
     */
    suspend fun check(serverUrl: String): UnreachableCase? = withContext(Dispatchers.IO) {
        if (serverEndpoint(serverUrl) == null) return@withContext null
        val answer = askVersion(serverUrl)
        if (answer == ServerAnswer.SillyTavern) return@withContext null
        val vpn = vpnUp()
        val connect = if (vpn) connect(serverUrl) else null
        connectionVerdict(vpn, answer, connect)
    }

    /** `GET /version`, no redirects followed, at most [MAX_VERSION_BYTES] read. Any failure is [ServerAnswer.None]. */
    private fun askVersion(serverUrl: String): ServerAnswer {
        var conn: HttpURLConnection? = null
        return try {
            val url = serverUrl.trimEnd('/') + "/version"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val body = if (code == 200) {
                conn.inputStream.use { stream -> readAtMost(stream, MAX_VERSION_BYTES) }?.toString(Charsets.UTF_8)
            } else null
            serverAnswerOf(code, conn.getHeaderField("Location"), body, serverUrl)
        } catch (_: Exception) {
            ServerAnswer.None
        } finally {
            conn?.disconnect()
        }
    }

    /** A ~2 s TCP connect to ST's host:port: null if it connected, else how it failed. */
    private fun connect(serverUrl: String): ConnectFailure? {
        val (host, port) = serverEndpoint(serverUrl) ?: return null
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            null
        } catch (e: Exception) {
            connectFailureOf(e) ?: ConnectFailure.Other
        }
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
        const val HTTP_TIMEOUT_MS = 2_500
        const val MAX_VERSION_BYTES = 64 * 1024
    }
}
