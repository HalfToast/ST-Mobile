package dev.halftoast.stmobile.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
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
    /** SillyTavern's login page is showing; the user must sign in. Not an error. */
    data object LoginRequired : ChatState
    data class Unreachable(val reason: String) : ChatState
}

/** Owns the single SillyTavern WebView for the lifetime of the Activity. */
class WebViewHolder(private val activity: ComponentActivity, private val appSettings: AppSettings) {

    private val handler = Handler(Looper.getMainLooper())
    private val downloads = Downloads(activity, handler)

    var webView: WebView = createWebView()
        private set

    var interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state

    private val readyCallbacks = mutableListOf<(Long) -> Unit>()
    private var parent: ViewGroup? = null
    private var loadStartedAt = 0L
    private var loadGeneration = 0
    private var mainFrameFailed = false
    private var pollingGeneration = -1
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
     * Back-press probe: counts open UI, closes the top dialog (ST closes popups on the native
     * `cancel` event, which a synthetic Escape keydown never produces) or else sends Escape to the
     * focused element, then re-counts 150 ms later.
     * [result] receives true when something closed.
     */
    fun probeBack(result: (consumedByPage: Boolean) -> Unit) {
        webView.evaluateJavascript(
            "(function(){var n=document.querySelectorAll('$OPEN_UI_SELECTOR').length;" +
                "var d=document.querySelectorAll('dialog[open]'),t=d[d.length-1];if(t){t.dispatchEvent(new Event('cancel',{cancelable:true}));}else{(document.activeElement||document).dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));}return n;})()",
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
        loadUrl(appSettings.serverUrl)
    }

    private fun startReadyPolling() {
        val gen = loadGeneration
        if (pollingGeneration == gen) return
        pollingGeneration = gen
        val view = webView
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        lateinit var poll: Runnable
        poll = Runnable {
            if (gen != loadGeneration || view !== webView) return@Runnable
            view.evaluateJavascript(READY_PROBE) { r ->
                if (gen != loadGeneration || view !== webView) return@evaluateJavascript
                when (r) {
                    "\"ready\"" -> {
                        val elapsed = SystemClock.elapsedRealtime() - loadStartedAt
                        Log.i(StMobileApp.LOG_TAG, "ST_READY ms=$elapsed")
                        _state.value = ChatState.Ready
                        readyCallbacks.toList().forEach { it(elapsed) }
                    }
                    "\"login\"" -> _state.value = ChatState.LoginRequired
                    else -> if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(poll, POLL_INTERVAL_MS)
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
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        downloads.install(this, appSettings.serverUrl)

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor?.invoke(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                openExternallyIfForeign(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadGeneration++
                mainFrameFailed = false
                loadStartedAt = SystemClock.elapsedRealtime()
                _state.value = ChatState.Loading
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame || response.statusCode < 400) return
                mainFrameFailed = true
                val code = response.statusCode
                val hint = when (code) {
                    403 -> " Check the whitelist."
                    401 -> " Authentication required."
                    else -> ""
                }
                _state.value = ChatState.Unreachable("Server refused the connection ($code).$hint")
            }

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
            /** target=_blank / window.open: never open a second WebView; hand the URL to the browser. */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(activity)
                var handled = false
                fun handle(uri: Uri) {
                    if (handled || uri.scheme == "about") return
                    handled = true
                    if (!openExternallyIfForeign(uri)) view.loadUrl(uri.toString()) // same origin: stay here
                    handler.post { popup.destroy() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        handle(request.url)
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        url?.let { handle(Uri.parse(it)) }
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }

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
            downloads.onDownloadStart(this, url, userAgent, contentDisposition, mimeType)
        }
    }

    /** True (and launches the default browser) for http(s) URLs outside the server origin. */
    private fun openExternallyIfForeign(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false // blob:, data:, etc. untouched
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://${uri.host}$port"
        if (origin.equals(Downloads.originOf(appSettings.serverUrl), ignoreCase = true)) return false
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            Log.w(StMobileApp.LOG_TAG, "no browser for $uri", e)
        }
        return true
    }

    /** SAF wants MIME types; ST sends extensions like ".png" too. */
    private fun toMime(t: String): String =
        if (t.startsWith(".")) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) ?: "*/*"
        } else t

    private companion object {
        /** "ready" needs a positive ST marker; the login page has neither marker. */
        const val READY_PROBE = "(function(){if(location.pathname.indexOf('/login')===0)return 'login';" +
            "return (document.getElementById('preloader')===null&&document.getElementById('send_textarea')!==null)" +
            "?'ready':'wait';})()"
        const val POLL_INTERVAL_MS = 200L
        const val READY_TIMEOUT_MS = 60_000L
        const val OPEN_UI_SELECTOR = ".openDrawer, dialog[open], #dialogue_popup:not([style*=\"none\"]), " +
            "#character_popup:not([style*=\"none\"]), #select_chat_popup:not([style*=\"none\"])"
    }
}
