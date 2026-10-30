package dev.halftoast.stmobile.chat

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.halftoast.stmobile.StMobileApp

/**
 * Chat messages drop the empty lines Chromium (unlike Firefox) draws for line breaks that come before
 * any content, e.g. a paragraph that starts with `<br><br>` after a template's tracker block. The page
 * script ([ASSET_PATH]) does the work; without document-start scripts messages look as they do in
 * Chrome.
 */
object ChatBreaks {
    const val ASSET_PATH = "web/chat-breaks.js"

    fun load(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

    /** Installs [script] for [origin] (scheme://host[:port]) only; nothing for any other origin. */
    fun install(webView: WebView, origin: String?, script: String) {
        if (origin == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        } else {
            Log.w(StMobileApp.LOG_TAG, "DOCUMENT_START_SCRIPT unsupported; chat keeps Chromium's leading breaks")
        }
    }
}
