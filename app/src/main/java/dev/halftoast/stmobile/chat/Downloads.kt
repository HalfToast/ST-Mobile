package dev.halftoast.stmobile.chat

import android.app.DownloadManager
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
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
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Download handling. http(s) goes to DownloadManager. blob: URLs are read inside the page
 * (SillyTavern revokes blob URLs right after click, so a document-start script delays the
 * revoke and records the anchor's download name) and streamed back in ~1 MB pieces through an
 * origin-restricted web message listener, one single-use nonce per download (see [BlobReceiver]).
 * Each piece is appended to a pending MediaStore item, so a backup export of hundreds of MB never
 * sits in memory; the page sends the next piece only after the previous one was written.
 */
class Downloads(activity: ComponentActivity, private val handler: Handler) {

    private val app = activity.applicationContext
    private val random = SecureRandom()

    /** All blob work runs here, in message order. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "st-blob-download") }
    private val receiver = BlobReceiver(
        sink = MediaStoreSink(app.contentResolver),
        now = SystemClock::elapsedRealtime,
        report = ::toast,
        log = { Log.w(StMobileApp.LOG_TAG, it) },
    )
    private var sweeper: ScheduledFuture<*>? = null // worker thread only

    /** Installs the page hooks for [origin] (scheme://host[:port]); nothing is installed for any other origin. */
    fun install(webView: WebView, origin: String?) {
        if (origin == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOCUMENT_START_SCRIPT, setOf(origin))
        } else {
            Log.w(StMobileApp.LOG_TAG, "DOCUMENT_START_SCRIPT unsupported; blob downloads may fail")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, reply ->
                if (isMainFrame) onMessage(message, reply)
            }
        } else {
            Log.w(StMobileApp.LOG_TAG, "WEB_MESSAGE_LISTENER unsupported; blob downloads disabled")
        }
    }

    fun onDownloadStart(webView: WebView, url: String, userAgent: String?, disposition: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> downloadBlob(webView, url, disposition, mime)
            url.startsWith("http:") || url.startsWith("https:") -> downloadHttp(url, userAgent, disposition, mime)
        }
    }

    private fun downloadHttp(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            mime?.takeIf { it.isNotBlank() }?.let { req.setMimeType(it) }
            // An empty Cookie header is worse than none; only send what the WebView actually has.
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("User-Agent", it) }
            app.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            Log.w(StMobileApp.LOG_TAG, "DownloadManager refused the download", e)
            toast(BlobReceiver.FAILED)
        }
    }

    private fun downloadBlob(webView: WebView, url: String, disposition: String?, mime: String?) {
        val nonce = ByteArray(BlobReceiver.NONCE_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val fallback = URLUtil.guessFileName(url, disposition, mime)
        // Queued before the script runs, so the page's "start" can never overtake it.
        worker.execute {
            receiver.expect(nonce, fallback)
            ensureSweeping()
        }
        val binary = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        webView.evaluateJavascript(transferScript(url, nonce, mime.orEmpty(), binary), null)
    }

    /** The holder is being destroyed: abort open transfers (no partial file is left) and stop the worker. */
    fun close() {
        try {
            worker.execute { receiver.abortAll() }
            worker.shutdown()
        } catch (_: Exception) {
        }
    }

    /** UI thread. Copies the message out, then parses and writes on [worker]. */
    private fun onMessage(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        try {
            val bytes = if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) message.arrayBuffer else null
            val text = if (bytes == null) message.data ?: return else null
            worker.execute {
                val answer = try {
                    if (bytes != null) receiver.onBinary(bytes) else receiver.onText(text!!)
                } catch (t: Throwable) { // OutOfMemoryError included: never take the app down for a download
                    Log.w(StMobileApp.LOG_TAG, "blob message failed", t)
                    null
                }
                if (answer != null) handler.post { runCatching { reply.postMessage(answer) } }
            }
        } catch (t: Throwable) {
            Log.w(StMobileApp.LOG_TAG, "blob message dropped", t)
        }
    }

    /** Worker thread. Sweeps stalled transfers every few seconds while any download is open. */
    private fun ensureSweeping() {
        if (sweeper != null) return
        sweeper = worker.scheduleWithFixedDelay({
            try {
                receiver.sweep()
            } catch (t: Throwable) {
                Log.w(StMobileApp.LOG_TAG, "blob sweep failed", t)
            }
            if (receiver.isIdle) {
                sweeper?.cancel(false)
                sweeper = null
            }
        }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }

    /** A pending (IS_PENDING) item in Downloads, published on commit, deleted on abort. */
    private class MediaStoreSink(private val resolver: ContentResolver) : DownloadSink {
        override fun open(name: String, mime: String): DownloadSink.Output {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                if (mime.isNotEmpty()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            val stream: OutputStream = try {
                resolver.openOutputStream(uri) ?: error("no output stream")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            return object : DownloadSink.Output {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = stream.write(bytes, offset, length)

                override fun commit() {
                    stream.close()
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }

                override fun abort() {
                    runCatching { stream.close() }
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }

    companion object {
        private const val BRIDGE_NAME = "STMobileDownloads"
        private const val CHUNK_BYTES = 1 shl 20
        private const val SWEEP_MS = 5_000L

        /**
         * Reads the blob in [CHUNK_BYTES] slices and posts them one at a time, each after the app's
         * `ack:<nonce>` (so at most one slice is in flight). [binary]: ArrayBuffer messages prefixed
         * with the 16 nonce bytes; otherwise base64 JSON. A null/odd FileReader result or a failed
         * fetch posts `fail`, and nothing is saved.
         */
        internal fun transferScript(url: String, nonce: String, mime: String, binary: Boolean): String {
            val q = JSONObject::quote
            return """(function(){
var u=${q(url)},n=${q(nonce)},fm=${q(mime)},bin=$binary,B=$BRIDGE_NAME,CH=$CHUNK_BYTES;
var names=window.__stmBlobNames,name=names&&names.get(u)||'';
var nb=new Uint8Array(16);for(var i=0;i<16;i++)nb[i]=parseInt(n.substr(i*2,2),16);
var b=null,off=0,dead=false;
function post(o){B.postMessage(JSON.stringify(o));}
function stop(){dead=true;B.removeEventListener('message',onReply);}
function fail(e){if(dead)return;stop();post({n:n,t:'fail',error:String(e)});}
function onReply(e){if(dead)return;if(e.data==='abort:'+n){stop();return;}if(e.data==='ack:'+n)next();}
function next(){
if(off>=b.size){stop();post({n:n,t:'done',size:b.size});return;}
var p=b.slice(off,Math.min(off+CH,b.size));off+=p.size;
if(bin){p.arrayBuffer().then(function(buf){if(dead)return;var m=new Uint8Array(16+buf.byteLength);m.set(nb,0);m.set(new Uint8Array(buf),16);B.postMessage(m.buffer);}).catch(fail);}
else{var f=new FileReader();f.onload=function(){if(dead)return;var s=f.result;if(typeof s!=='string'||s.indexOf(',')<0){fail('read failed');return;}post({n:n,t:'chunk',b64:s.substring(s.indexOf(',')+1)});};f.onerror=function(){fail(f.error);};f.readAsDataURL(p);}
}
B.addEventListener('message',onReply);
fetch(u).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.blob();}).then(function(x){if(!x)throw new Error('no data');b=x;post({n:n,t:'start',size:b.size,mime:b.type||fm,name:name});}).catch(fail);
})()"""
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
