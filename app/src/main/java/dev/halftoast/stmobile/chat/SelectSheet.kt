package dev.halftoast.stmobile.chat

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.halftoast.stmobile.StMobileApp

/**
 * SillyTavern's native `<select>` dropdowns open a themed bottom sheet inside the page instead of
 * Android's list dialog. The page script ([ASSET_PATH]) does the work; without document-start
 * scripts the native list stays.
 */
object SelectSheet {
    const val ASSET_PATH = "web/select-sheet.js"

    /** The web message listener the sheet posts "open" and "closed" to. */
    const val BRIDGE_NAME = "STMobileSheet"

    /** True for exactly "open", false for exactly "closed", null for anything else. */
    fun parseBridgeMessage(data: String?): Boolean? = when (data) {
        "open" -> true
        "closed" -> false
        else -> null
    }

    fun load(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

    /** Installs [script] for [origin] (scheme://host[:port]) only; nothing for any other origin. */
    fun install(webView: WebView, origin: String?, script: String) {
        if (origin == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        } else {
            Log.w(StMobileApp.LOG_TAG, "DOCUMENT_START_SCRIPT unsupported; selects use the native list")
        }
    }

    /**
     * Listens, for [origin]'s main frame only, for the sheet opening and closing. [onSheetOpen] runs on
     * the main thread. Without web message listeners nothing is reported (the floating button stays).
     */
    fun installBridge(webView: WebView, origin: String?, onSheetOpen: (Boolean) -> Unit) {
        if (origin == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(StMobileApp.LOG_TAG, "WEB_MESSAGE_LISTENER unsupported; the floating button stays over list sheets")
            return
        }
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            parseBridgeMessage(message.data)?.let(onSheetOpen)
        }
    }
}
