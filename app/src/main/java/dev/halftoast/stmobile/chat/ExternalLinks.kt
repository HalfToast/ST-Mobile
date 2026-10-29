package dev.halftoast.stmobile.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import dev.halftoast.stmobile.StMobileApp

/** Hands links to other apps. Never loads anything into the SillyTavern WebView. */
class ExternalLinks(private val context: Context) {

    fun open(uri: Uri, target: LinkTarget) {
        when (target) {
            LinkTarget.Ignore -> Log.i(StMobileApp.LOG_TAG, "link ignored (scheme ${uri.scheme})")
            LinkTarget.Browser -> start(browsable(Intent(Intent.ACTION_VIEW, uri)))
            LinkTarget.ExternalApp -> if (uri.scheme.equals("intent", ignoreCase = true)) openIntentUri(uri) else {
                start(browsable(Intent(Intent.ACTION_VIEW, uri)))
            }
        }
    }

    /** `intent:` links as Chrome treats them: no explicit component, browsable only, fallback URL if no app. */
    private fun openIntentUri(uri: Uri) {
        val intent = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            toast()
            return
        }
        intent.component = null
        intent.selector = null
        intent.flags = 0
        browsable(intent)
        val fallback = intent.getStringExtra("browser_fallback_url")
            ?.takeIf { LinkPolicy.forNewWindow(it) == LinkTarget.Browser }
        if (!start(intent, toastOnFailure = fallback == null) && fallback != null) {
            start(browsable(Intent(Intent.ACTION_VIEW, Uri.parse(fallback))))
        }
    }

    private fun browsable(intent: Intent) = intent.addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun start(intent: Intent, toastOnFailure: Boolean = true): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        if (toastOnFailure) toast()
        false
    } catch (e: SecurityException) {
        if (toastOnFailure) toast()
        false
    }

    private fun toast() = Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
}
