package dev.halftoast.stmobile.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Base64
import kotlin.concurrent.thread

sealed interface ChatState {
    data object Loading : ChatState
    data object Ready : ChatState
    data class Unreachable(val reason: String) : ChatState
}

/** Owns the single SillyTavern WebView for the lifetime of the Activity. */
class WebViewHolder(private val activity: ComponentActivity, private val appSettings: AppSettings) {

    var webView: WebView = createWebView()
        private set

    var interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state

    private val handler = Handler(Looper.getMainLooper())
    private val readyCallbacks = mutableListOf<(Long) -> Unit>()
    private var parent: ViewGroup? = null
    private var loadStartedAt = 0L
    private var loadGeneration = 0
    private var mainFrameFailed = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val multiPicker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        deliverFiles(it)
    }
    private val singlePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        deliverFiles(listOfNotNull(it))
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) CookieManager.getInstance().flush()
        })
        CookieManager.getInstance().setAcceptCookie(true)
        webView.loadUrlTimed()
    }

    fun attach(parent: ViewGroup) {
        this.parent = parent
        (webView.parent as? ViewGroup)?.takeIf { it !== parent }?.removeView(webView)
        if (webView.parent == null) {
            parent.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        parent = null
    }

    fun reload() {
        _state.value = ChatState.Loading
        webView.loadUrlTimed()
    }

    /** Builds a fresh WebView after the renderer died. Does not reload; call [reload]. */
    fun recreate() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        webView = createWebView()
        parent?.let { attach(it) }
    }

    /** Called every time the page finishes booting (preloader gone). */
    fun onStReady(callback: (elapsedMs: Long) -> Unit) {
        readyCallbacks += callback
    }

    /**
     * Back-press probe: counts open UI, sends Escape, re-counts 150 ms later.
     * [result] receives true when something closed.
     */
    fun probeBack(result: (consumedByPage: Boolean) -> Unit) {
        webView.evaluateJavascript(
            "(function(){var n=document.querySelectorAll('$OPEN_UI_SELECTOR').length;" +
                "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return n;})()",
        ) { beforeRaw ->
            val before = beforeRaw?.toIntOrNull() ?: 0
            handler.postDelayed({
                webView.evaluateJavascript("document.querySelectorAll('$OPEN_UI_SELECTOR').length") { afterRaw ->
                    val after = afterRaw?.toIntOrNull() ?: before
                    result(after < before)
                }
            }, 150)
        }
    }

    private fun WebView.loadUrlTimed() {
        loadGeneration++
        mainFrameFailed = false
        loadStartedAt = SystemClock.elapsedRealtime()
        loadUrl(appSettings.serverUrl)
    }

    private fun startReadyPolling() {
        val gen = loadGeneration
        val view = webView
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        lateinit var poll: Runnable
        poll = Runnable {
            if (gen != loadGeneration || view !== webView) return@Runnable
            view.evaluateJavascript("document.getElementById('preloader') === null") { r ->
                if (gen != loadGeneration || view !== webView) return@evaluateJavascript
                if (r == "true") {
                    val elapsed = SystemClock.elapsedRealtime() - loadStartedAt
                    Log.i(StMobileApp.LOG_TAG, "ST_READY ms=$elapsed")
                    _state.value = ChatState.Ready
                    readyCallbacks.toList().forEach { it(elapsed) }
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    handler.postDelayed(poll, POLL_INTERVAL_MS)
                }
            }
        }
        handler.post(poll)
    }

    private fun deliverFiles(uris: List<Uri>) {
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(): WebView = WebView(activity).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        addJavascriptInterface(BlobBridge(), BRIDGE_NAME)

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor?.invoke(request)

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush() // persist the login session promptly
                if (!mainFrameFailed) startReadyPolling()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    _state.value = ChatState.Unreachable(error.description?.toString() ?: "Unknown error")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(StMobileApp.LOG_TAG, "renderer gone (crashed=${detail.didCrash()}), recreating")
                this@WebViewHolder.recreate()
                this@WebViewHolder.reload()
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }
                    .filter { it.isNotEmpty() }.map(::toMime).distinct().ifEmpty { listOf("*/*") }.toTypedArray()
                try {
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) multiPicker.launch(types)
                    else singlePicker.launch(types)
                } catch (e: Exception) {
                    Log.w(StMobileApp.LOG_TAG, "file chooser failed", e)
                    deliverFiles(emptyList())
                    return false
                }
                return true
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) downloadBlob(url, contentDisposition, mimeType)
            else if (url.startsWith("http")) downloadHttp(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** SAF wants MIME types; ST sends extensions like ".png" too. */
    private fun toMime(t: String): String =
        if (t.startsWith(".")) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) ?: "*/*"
        } else t

    private fun downloadHttp(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent ?: "")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val name = URLUtil.guessFileName(url, disposition, mime)
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (activity.getSystemService(DownloadManager::class.java)).enqueue(req)
        Toast.makeText(activity, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    /** One-shot fetch -> base64 -> native; only runs when a blob download starts. */
    private fun downloadBlob(url: String, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val js = "(function(){fetch(${JSONObject.quote(url)}).then(function(r){return r.blob();}).then(function(b){" +
            "var f=new FileReader();f.onloadend=function(){var s=String(f.result);" +
            "$BRIDGE_NAME.save(s.substring(s.indexOf(',')+1),b.type||${JSONObject.quote(mime ?: "")},${JSONObject.quote(name)});};" +
            "f.readAsDataURL(b);}).catch(function(e){$BRIDGE_NAME.fail(String(e));});})()"
        webView.evaluateJavascript(js, null)
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun save(base64: String, mime: String, name: String) {
            thread {
                try {
                    val bytes = Base64.getDecoder().decode(base64)
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
        }

        @JavascriptInterface
        fun fail(message: String) = toast("Download failed: $message")

        private fun toast(msg: String) =
            handler.post { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val BRIDGE_NAME = "STMobileDownloads"
        const val POLL_INTERVAL_MS = 200L
        const val READY_TIMEOUT_MS = 60_000L
        const val OPEN_UI_SELECTOR = ".openDrawer, dialog[open], #dialogue_popup:not([style*=\"none\"]), " +
            "#character_popup:not([style*=\"none\"]), #select_chat_popup:not([style*=\"none\"])"
    }
}
