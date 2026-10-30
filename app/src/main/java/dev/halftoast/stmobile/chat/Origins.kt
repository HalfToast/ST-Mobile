package dev.halftoast.stmobile.chat

/** Web origins as the WebView's origin rules use them: `scheme://host[:port]`, lowercase, default port dropped. */
object Origins {
    private val AUTHORITY = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)")

    /** Origin of an http(s) URL; null for other schemes (blob:, about:, mailto: …) or garbage. */
    fun of(url: String?): String? {
        val m = AUTHORITY.find(url?.trim() ?: return null) ?: return null
        val scheme = m.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = m.groupValues[2].substringAfterLast('@')
        val host: String
        val port: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(0, end + 1)
            port = authority.substring(end + 1).removePrefix(":")
        } else {
            host = authority.substringBefore(':')
            port = if (':' in authority) authority.substringAfter(':') else ""
        }
        if (host.isEmpty()) return null
        val p = if (port.isEmpty()) null else port.toIntOrNull() ?: return null
        val explicit = p?.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        return "$scheme://${host.lowercase()}" + (explicit?.let { ":$it" } ?: "")
    }

    /** True if [url] is on [origin] (an [of] result). */
    fun isOn(url: String?, origin: String?): Boolean = origin != null && of(url) == origin
}

enum class LinkTarget {
    /** The default browser (ACTION_VIEW + BROWSABLE). */
    Browser,
    /** Another app: mailto:, tel:, sms:, geo:, intent: … */
    ExternalApp,
    /** Never opened anywhere. */
    Ignore,
}

/** Where links go. Nothing that opens a "new window" may load into the single SillyTavern WebView. */
object LinkPolicy {
    private val NEVER_EXTERNAL = setOf("about", "blob", "data", "javascript", "file", "content", "filesystem")

    private fun scheme(url: String): String? =
        Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(url.trim())?.groupValues?.get(1)?.lowercase()

    /** target=_blank / window.open: http(s), same origin included, opens in the browser like a new Chrome tab. */
    fun forNewWindow(url: String): LinkTarget = when (scheme(url)) {
        "http", "https" -> LinkTarget.Browser
        null -> LinkTarget.Ignore
        in NEVER_EXTERNAL -> LinkTarget.Ignore
        else -> LinkTarget.ExternalApp
    }

    /**
     * A same-window main-frame navigation. Null: the WebView loads it (http(s) of any origin, as a browser
     * does, so OAuth sign-ins and their callbacks work; about:/blob:/data: as Chromium handles them).
     * mailto:, tel:, intent: … go to their app instead of an "unknown URL scheme" error page;
     * file:/content: are refused.
     */
    fun forMainFrame(url: String): LinkTarget? = when (scheme(url)) {
        "http", "https", "about", "blob", "data", "javascript" -> null
        null, "file", "content", "filesystem" -> LinkTarget.Ignore
        else -> LinkTarget.ExternalApp
    }
}
