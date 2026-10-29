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
import dev.halftoast.stmobile.BuildConfig
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloads = Downloads(activity, mainHandler)

    var webView: WebView = createWebView()
        private set

    @Volatile
    var interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state

    private val assetCache = AssetCache(File(activity.cacheDir, "asset-cache"))
    private var navToken = 0
    private val resolvingAfterReady = java.util.concurrent.atomic.AtomicBoolean(false)

    private val readyCallbacks = mutableListOf<(Long) -> Unit>()
    private var parent: ViewGroup? = null
    private var loadStartedAt = 0L
    private var loadGeneration = 0
    private var mainFrameFailed = false
    private var pollingGeneration = -1
    private var pendingHttpError: String? = null
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
        interceptor = ::serveFromAssetCache
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

    /**
     * Drops every cached asset and re-resolves the server revision, then reloads (call after a successful ST update).
     */
    fun revalidate() {
        _state.value = ChatState.Loading
        webView.loadUrlTimed(clearFirst = true)
    }

    fun reload() {
        _state.value = ChatState.Loading
        webView.loadUrlTimed()
    }

    /**
     * The Control Center saw the server stop: show Unreachable now instead of the stale page. Loads
     * nothing (a pending load and readiness polling are dropped); Retry or [reload] loads again.
     */
    fun markServerDown(reason: String) {
        navToken++
        loadGeneration++
        webView.stopLoading()
        _state.value = ChatState.Unreachable(reason)
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

    /** Off-origin page in the WebView (e.g. OAuth provider) with history: step back instead of probing. */
    fun goBackIfOffOrigin(): Boolean {
        val url = webView.url?.let(Uri::parse) ?: return false
        val port = if (url.port == -1) "" else ":${url.port}"
        val origin = "${url.scheme}://${url.host}$port"
        if (origin.equals(Downloads.originOf(appSettings.serverUrl), ignoreCase = true) || !webView.canGoBack()) return false
        webView.goBack()
        return true
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
            mainHandler.postDelayed({
                webView.evaluateJavascript("document.querySelectorAll('$OPEN_UI_SELECTOR').length") { afterRaw ->
                    val after = afterRaw?.toIntOrNull() ?: before
                    result(after < before)
                }
            }, 150)
        }
    }

    private fun cacheDisabled() = BuildConfig.DEBUG && appSettings.debugDisableAssetCache

    /** Resolves the cache namespace (off the main thread, total wait capped), then loads. */
    private fun WebView.loadUrlTimed(clearFirst: Boolean = false) {
        val serverUrl = appSettings.serverUrl
        val token = ++navToken
        thread(name = "st-version") {
            if (clearFirst) assetCache.clear()
            val start = SystemClock.elapsedRealtime()
            val revision = if (cacheDisabled()) null else resolveRevision(serverUrl)
            assetCache.setRevision(revision)
            Log.i(StMobileApp.LOG_TAG, "VERSION_WAIT ms=${SystemClock.elapsedRealtime() - start} revision=${revision ?: "null"}")
            mainHandler.post {
                if (token == navToken) this@WebViewHolder.webView.loadUrl(serverUrl)
            }
        }
    }

    /** Hard 5 s ceiling on the whole /version round trip (connect + read are each capped too). */
    private fun resolveRevision(serverUrl: String): String? {
        val task = java.util.concurrent.FutureTask<String?> { fetchRevision(serverUrl) }
        thread(isDaemon = true, name = "st-version-fetch") { task.run() }
        return try {
            task.get(VERSION_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            null
        }
    }

    /** /version needs a session, so a fresh login loads uncached; once Ready, resolve it for later loads. */
    private fun resolveRevisionAfterReady() {
        if (cacheDisabled() || assetCache.revision != null) return
        if (!resolvingAfterReady.compareAndSet(false, true)) return
        val serverUrl = appSettings.serverUrl
        thread(name = "st-version-late") {
            try {
                resolveRevision(serverUrl)?.let { assetCache.setRevision(it) }
            } finally {
                resolvingAfterReady.set(false)
            }
        }
    }

    private fun fetchRevision(serverUrl: String): String? = try {
        val url = serverUrl.trimEnd('/') + "/version"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = VERSION_TIMEOUT_MS
            conn.readTimeout = VERSION_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            cookieHeader(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode == 200) parseCacheNamespace(conn.inputStream.bufferedReader().readText()) else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(StMobileApp.LOG_TAG, "version fetch failed: ${e.message}")
        null
    }

    private fun cookieHeader(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    /** Runs on Chromium IO threads. Null means "let the WebView fetch it". */
    private fun serveFromAssetCache(request: WebResourceRequest): WebResourceResponse? {
        if (cacheDisabled()) return null
        val rev = assetCache.revision ?: return null
        val uri = request.url
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (!request.method.equals("GET", ignoreCase = true) || request.requestHeaders.containsKey("Range")) return null
        if (!AssetCachePolicy.shouldCache("GET", uri.path ?: return null)) return null
        val url = uri.toString()
        assetCache.get(url)?.let { return WebResourceResponse(it.mime, it.encoding, it.bytes.inputStream()) }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = FETCH_TIMEOUT_MS
                conn.readTimeout = FETCH_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                request.requestHeaders["User-Agent"]?.let { conn.setRequestProperty("User-Agent", it) }
                request.requestHeaders["Accept-Language"]?.let { conn.setRequestProperty("Accept-Language", it) }
                cookieHeader(url)?.let { conn.setRequestProperty("Cookie", it) }
                if (conn.responseCode != 200) return null
                val contentType = conn.contentType ?: return null
                val mime = contentType.substringBefore(';').trim().ifEmpty { return null }
                val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
                    ?.groupValues?.get(1)?.trim('"')
                val bytes = conn.inputStream.use { it.readBytes() }
                assetCache.put(url, mime, charset, bytes, expectedRevision = rev)
                WebResourceResponse(mime, charset, bytes.inputStream())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
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
                        resolveRevisionAfterReady()
                        readyCallbacks.toList().forEach { it(elapsed) }
                    }
                    "\"login\"" -> _state.value = ChatState.LoginRequired
                    else -> if (SystemClock.elapsedRealtime() < deadline) mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
                }
            }
        }
        mainHandler.post(poll)
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

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadGeneration++
                mainFrameFailed = false
                loadStartedAt = SystemClock.elapsedRealtime()
                // onReceivedHttpError fires before the navigation commits; keep its verdict.
                val refused = pendingHttpError
                if (refused != null) {
                    mainFrameFailed = true
                    _state.value = ChatState.Unreachable(refused)
                } else {
                    _state.value = ChatState.Loading
                }
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
                val reason = "Server refused the connection ($code).$hint"
                pendingHttpError = reason
                _state.value = ChatState.Unreachable(reason)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush() // persist the login session promptly
                pendingHttpError = null
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
                    mainHandler.post { popup.destroy() }
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
        const val VERSION_TIMEOUT_MS = 5_000
        const val FETCH_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 200L
        const val READY_TIMEOUT_MS = 60_000L
        const val OPEN_UI_SELECTOR = ".openDrawer, dialog[open], #dialogue_popup:not([style*=\"none\"]), " +
            "#character_popup:not([style*=\"none\"]), #select_chat_popup:not([style*=\"none\"])"
    }
}
