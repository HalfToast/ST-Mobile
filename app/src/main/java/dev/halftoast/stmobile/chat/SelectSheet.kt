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
}
