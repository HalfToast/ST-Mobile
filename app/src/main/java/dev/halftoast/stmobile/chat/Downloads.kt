package dev.halftoast.stmobile.chat

import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.halftoast.stmobile.StMobileApp
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Download handling. http(s) goes to DownloadManager. blob: URLs are fetched inside the page
 * (SillyTavern revokes blob URLs right after click, so a document-start script delays the
 * revoke and records the anchor's download name) and posted back through an origin-restricted
 * web message listener, one single-use nonce per download.
 */
class Downloads(private val activity: ComponentActivity, private val handler: Handler) {

    private val pendingNonces: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val random = SecureRandom()

    /** Scheme://host[:port] of the server, as used by the WebView origin rules. */
    fun install(webView: WebView, serverUrl: String) {
        val origin = originOf(serverUrl) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOCUMENT_START_SCRIPT, setOf(origin))
        } else {
            Log.w(StMobileApp.LOG_TAG, "DOCUMENT_START_SCRIPT unsupported; blob downloads may fail")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onMessage(message)
            }
        } else {
            Log.w(StMobileApp.LOG_TAG, "WEB_MESSAGE_LISTENER unsupported; blob downloads disabled")
        }
    }

    fun onDownloadStart(webView: WebView, url: String, userAgent: String?, disposition: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> downloadBlob(webView, url, disposition, mime)
            url.startsWith("http") -> downloadHttp(url, userAgent, disposition, mime)
        }
    }

    private fun downloadHttp(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent ?: "")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val name = URLUtil.guessFileName(url, disposition, mime)
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        activity.getSystemService(DownloadManager::class.java).enqueue(req)
        Toast.makeText(activity, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    private fun downloadBlob(webView: WebView, url: String, disposition: String?, mime: String?) {
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        pendingNonces += nonce
        val q = JSONObject::quote
        val js = "(function(){var u=${q(url)},n=${q(nonce)},names=window.__stmBlobNames;" +
            "var name=names&&names.get(u)||'';" +
            "fetch(u).then(function(r){return r.blob();}).then(function(b){" +
            "var f=new FileReader();f.onloadend=function(){var s=String(f.result);" +
            "$BRIDGE_NAME.postMessage(JSON.stringify({n:n,ok:true,b64:s.substring(s.indexOf(',')+1)," +
            "mime:b.type||${q(mime ?: "")},name:name}));};f.readAsDataURL(b);})" +
            ".catch(function(e){$BRIDGE_NAME.postMessage(JSON.stringify({n:n,ok:false,error:String(e)}));});})()"
        webView.evaluateJavascript(js, null)
        // Disposition only matters for the fallback name, which is resolved on receipt.
        fallbackNames[nonce] = URLUtil.guessFileName(url, disposition, mime)
    }

    private val fallbackNames: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    private fun onMessage(message: WebMessageCompat) {
        val text = message.data ?: return
        thread {
            try {
                val json = JSONObject(text)
                val nonce = json.optString("n")
                if (!pendingNonces.remove(nonce)) return@thread // unknown or already used
                val fallback = fallbackNames.remove(nonce) ?: "download"
                if (!json.optBoolean("ok")) {
                    toast("Download failed: ${json.optString("error")}")
                    return@thread
                }
                val name = sanitize(json.optString("name")).ifEmpty { fallback }
                save(Base64.getDecoder().decode(json.getString("b64")), json.optString("mime"), name)
            } catch (e: Exception) {
                Log.w(StMobileApp.LOG_TAG, "blob message rejected", e)
            }
        }
    }

    private fun save(bytes: ByteArray, mime: String, name: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                if (mime.isNotEmpty()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            toast("Saved $name to Downloads")
        } catch (e: Exception) {
            Log.w(StMobileApp.LOG_TAG, "blob save failed", e)
            toast("Download failed: ${e.message}")
        }
    }

    private fun sanitize(name: String) = name.substringAfterLast('/').substringAfterLast('\\').trim()

    private fun toast(msg: String) = handler.post { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }

    companion object {
        private const val BRIDGE_NAME = "STMobileDownloads"

        fun originOf(serverUrl: String): String? = try {
            val u = URI(serverUrl.trim())
            val port = if (u.port == -1) "" else ":${u.port}"
            "${u.scheme}://${u.host}$port"
        } catch (_: Exception) {
            null
        }

        /**
         * Runs before page scripts on the server origin. Delays blob revocation by 30 s and records
         * the download name of blob anchors (programmatic a.click() on detached anchors included).
         */
        private const val DOCUMENT_START_SCRIPT = """(function(){
var names=new Map();
Object.defineProperty(window,'__stmBlobNames',{value:names});
var rev=URL.revokeObjectURL;
URL.revokeObjectURL=function(u){
if(typeof u==='string'&&u.indexOf('blob:')===0){setTimeout(function(){rev.call(URL,u);names.delete(u);},30000);return;}
return rev.apply(URL,arguments);};
function rec(a){var h=a.getAttribute('href');if(h&&h.indexOf('blob:')===0&&a.hasAttribute('download'))names.set(h,a.getAttribute('download'));}
var click=HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click=function(){rec(this);return click.apply(this,arguments);};
document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a');if(a)rec(a);},true);
})();"""
    }
}
