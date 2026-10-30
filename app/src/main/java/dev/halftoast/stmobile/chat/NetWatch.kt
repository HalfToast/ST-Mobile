package dev.halftoast.stmobile.chat

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.halftoast.stmobile.StMobileApp

/**
 * The page tells the app when one of SillyTavern's own requests fails at the network level
 * ([ASSET_PATH] posts "fail" to [BRIDGE_NAME]). The app then checks the connection; see
 * [ConnectionWatch]. Without document-start scripts or web message listeners only the VPN is watched.
 */
object NetWatch {
    const val ASSET_PATH = "web/net-watch.js"
    const val BRIDGE_NAME = "STMobileNet"

    fun load(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

    /** Exactly "fail". */
    fun isFailureMessage(data: String?): Boolean = data == "fail"

    /** Installs [script] and its listener for [origin]'s main frame only. [onFailure] runs on the main thread. */
    fun install(webView: WebView, origin: String?, script: String, onFailure: () -> Unit) {
        if (origin == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            Log.w(StMobileApp.LOG_TAG, "page scripts unsupported; only the VPN is watched for a lost connection")
            return
        }
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame && isFailureMessage(message.data)) onFailure()
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
    }
}
