package dev.halftoast.stmobile.chat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.halftoast.stmobile.BuildConfig
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Owns the single SillyTavern WebView for the lifetime of the Activity, and destroys it with the
 * Activity. After [destroy], every entry point is a no-op and pending callbacks are dropped.
 */
class WebViewHolder(
    private val activity: ComponentActivity,
    private val appSettings: AppSettings,
    private val onTheme: (Map<String, String>) -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloads = Downloads(activity, mainHandler)
    private val links = ExternalLinks(activity)
    private val selectSheetScript = SelectSheet.load(activity)
    private val chatBreaksScript = ChatBreaks.load(activity)
    private val netWatchScript = NetWatch.load(activity)

    /** Origin the current WebView's page hooks were installed for. Read on Chromium IO threads too. */
    @Volatile private var serverOrigin: String? = Origins.of(appSettings.serverUrl)

    var webView: WebView = createWebView()
        private set

    @Volatile
    var interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state

    private val _loadAttempts = MutableStateFlow(0)
    /**
     * Bumped on every load attempt (each time Loading is entered). A Retry that fails at once can go
     * Unreachable → Loading → Unreachable within a frame; this still changes, so the Unreachable
     * screen knows to look again.
     */
    val loadAttempts: StateFlow<Int> = _loadAttempts

    private val _cover = MutableStateFlow<UnreachableCase?>(null)
    /**
     * The connection was lost under a loaded page ([ConnectionMonitor]): Chat shows this Unreachable
     * case over the page, which stays loaded underneath. Only ever set while [state] is one of
     * [coversOnConnectionLoss]; any other state (a reload, a failed load) drops it.
     */
    val cover: StateFlow<UnreachableCase?> = _cover

    /** One of ST's own requests failed at the network level (net-watch.js). Main thread. */
    var onNetworkFailure: (() -> Unit)? = null

    private val _sheetOpen = MutableStateFlow(false)
    /** One of ST's lists is open as a sheet (select-sheet.js). Only ever true while [state] is Ready. */
    val sheetOpen: StateFlow<Boolean> = _sheetOpen

    /** Every state change goes through here: leaving Ready forgets an open sheet. */
    private fun setState(s: ChatState) {
        _state.value = s
        if (s != ChatState.Ready) _sheetOpen.value = false
        if (!s.coversOnConnectionLoss) _cover.value = null
    }

    /**
     * Covers the loaded page with [case] (it stays loaded: no reload, no new WebView). Nothing under the
     * cover can be used, so an open list sheet closes, focus leaves the page and the keyboard hides.
     */
    fun cover(case: UnreachableCase) {
        if (destroyed || !_state.value.coversOnConnectionLoss) return
        if (_cover.value == null) {
            // Nothing under the cover may take input: no open sheet, no focused field, no keyboard.
            webView.evaluateJavascript(
                "window.__stmSelectSheet&&window.__stmSelectSheet.close();" +
                    "if(document.activeElement&&document.activeElement.blur)document.activeElement.blur();",
                null,
            )
            _sheetOpen.value = false
            webView.clearFocus()
            activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
        _cover.value = case
    }

    /** The connection is back and nothing was lost: show the page exactly as it was. */
    fun uncover() {
        _cover.value = null
    }

    private val assetCache = AssetCache(File(activity.cacheDir, "asset-cache"))
    private var navToken = 0
    private val resolvingAfterReady = java.util.concurrent.atomic.AtomicBoolean(false)

    private var parent: ViewGroup? = null
    private var destroyed = false
    private var loadStartedAt = 0L
    /** Bumped on every main-frame navigation, recreate, markServerDown and destroy; stale callbacks compare it. */
    private var loadGeneration = 0
    /** Bumped every time Loading is entered; the ready deadline only fires for its own Loading. */
    private var loadingEpoch = 0
    private var mainFrameFailed = false
    /** The committed main frame is on [serverOrigin]; only then is readiness polled. */
    private var onServerPage = false
    private var pollingGeneration = -1
    private var pendingHttpError: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    /** Renderer crashes in the last [CRASH_WINDOW_MS], for the recreate+reload backoff. */
    private val crashTimes = ArrayDeque<Long>()
    /** Throwaway WebViews from onCreateWindow, destroyed once they reveal their URL or time out. */
    private val popups = mutableSetOf<WebView>()

    private val multiPicker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        deliverFiles(it)
    }
    private val singlePicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        deliverFiles(listOfNotNull(it))
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> CookieManager.getInstance().flush()
                // This holder belongs to this Activity instance; a recreated Activity (rare: the
                // manifest handles config changes) builds its own, so the WebView never outlives it.
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        })
        CookieManager.getInstance().setAcceptCookie(true)
        interceptor = ::serveFromAssetCache
        enterLoading()
        loadUrlTimed()
    }

    fun attach(parent: ViewGroup) {
        if (destroyed) return
        this.parent = parent
        (webView.parent as? ViewGroup)?.takeIf { it !== parent }?.removeView(webView)
        if (webView.parent == null) {
            parent.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    /**
     * [parent] is going away. Only takes the WebView out if it is still in [parent]: a newer parent may
     * already hold it (screen transitions can attach the next host before the old one is released).
     */
    fun detach(parent: ViewGroup) {
        if (webView.parent === parent) parent.removeView(webView)
        if (this.parent === parent) this.parent = null
    }

    fun reload() {
        if (destroyed) return
        loadGeneration++ // the old page's readiness poll must not mark this load Ready
        enterLoading()
        loadUrlTimed()
    }

    /** The user's Retry: also forgets earlier renderer crashes. */
    fun retry() {
        crashTimes.clear()
        reload()
    }

    /**
     * The Control Center saw the server stop: show Unreachable now instead of the stale page. Loads
     * nothing (a pending load and readiness polling are dropped); Retry or [reload] loads again.
     */
    fun markServerDown(reason: String) {
        if (destroyed) return
        navToken++
        loadGeneration++
        webView.stopLoading()
        setState(ChatState.Unreachable(reason))
    }

    /** The server URL changed: page hooks are bound to the old origin, so build a new WebView and load. */
    fun onServerUrlChanged() {
        if (destroyed) return
        recreate()
        reload()
    }

    /** Off-origin page in the WebView (e.g. OAuth provider) with history: step back instead of probing. */
    fun goBackIfOffOrigin(): Boolean {
        if (destroyed || Origins.isOn(webView.url, serverOrigin) || !webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    /**
     * Back-press probe: counts open UI, closes the top dialog (ST closes popups on the native
     * `cancel` event, which a synthetic Escape keydown never produces) or else sends Escape to the
     * focused element, then re-counts 150 ms later.
     * [result] receives true when something closed; false also if the page doesn't answer within 1 s.
     */
    fun probeBack(result: (consumedByPage: Boolean) -> Unit) {
        if (destroyed) return
        val view = webView
        var answered = false
        fun answer(consumed: Boolean) {
            if (answered) return
            answered = true
            result(consumed)
        }
        mainHandler.postDelayed({ answer(false) }, PROBE_BACK_TIMEOUT_MS)
        view.evaluateJavascript(BACK_PROBE_JS) { beforeRaw ->
            val before = beforeRaw?.toIntOrNull() ?: 0
            mainHandler.postDelayed({
                if (destroyed || view !== webView) return@postDelayed answer(false)
                view.evaluateJavascript(OPEN_UI_COUNT_JS) { afterRaw ->
                    val after = afterRaw?.toIntOrNull() ?: before
                    answer(after < before)
                }
            }, 150)
        }
    }

    /**
     * How tall SillyTavern's message box area is from the page's bottom, in CSS px; null if the page
     * has none (or doesn't answer in time).
     */
    fun measureSendForm(result: (Float?) -> Unit) {
        if (destroyed) return result(null)
        var answered = false
        fun answer(gap: Float?) {
            if (answered) return
            answered = true
            result(gap)
        }
        mainHandler.postDelayed({ answer(null) }, PROBE_BACK_TIMEOUT_MS)
        webView.evaluateJavascript(SEND_FORM_GAP_JS) { answer(it?.toFloatOrNull()?.takeIf { gap -> gap > 0f }) }
    }

    /** Builds a fresh WebView (renderer died, server URL changed). Does not load; call [reload]. */
    private fun recreate() {
        navToken++
        loadGeneration++
        _sheetOpen.value = false
        cancelFileChooser()
        destroyView(webView)
        serverOrigin = Origins.of(appSettings.serverUrl)
        webView = createWebView()
        parent?.let { attach(it) }
    }

    private fun destroy() {
        if (destroyed) return
        destroyed = true
        navToken++
        loadGeneration++
        loadingEpoch++
        mainHandler.removeCallbacksAndMessages(null)
        cancelFileChooser()
        popups.toList().forEach(::destroyView)
        popups.clear()
        destroyView(webView)
        downloads.close()
        parent = null
        Log.i(StMobileApp.LOG_TAG, "WEBVIEW_DESTROYED")
    }

    private fun destroyView(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun cancelFileChooser() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }

    /** Shows the snapshot overlay and arms the ready deadline: Loading never outlasts [READY_TIMEOUT_MS]. */
    private fun enterLoading() {
        _loadAttempts.value++
        setState(ChatState.Loading)
        val epoch = ++loadingEpoch
        mainHandler.postDelayed({
            if (!destroyed && epoch == loadingEpoch && _state.value == ChatState.Loading) {
                Log.i(StMobileApp.LOG_TAG, "READY_TIMEOUT: showing the page as it is")
                setState(ChatState.Stalled)
            }
        }, READY_TIMEOUT_MS)
    }

    private fun cacheDisabled() = BuildConfig.DEBUG && appSettings.debugDisableAssetCache

    /** Resolves the cache namespace (off the main thread, total wait capped), then loads. */
    private fun loadUrlTimed() {
        val serverUrl = appSettings.serverUrl
        val token = ++navToken
        pendingHttpError = null
        thread(name = "st-version") {
            val start = SystemClock.elapsedRealtime()
            val revision = if (cacheDisabled()) null else resolveRevision(serverUrl)
            assetCache.setRevision(revision)
            Log.i(StMobileApp.LOG_TAG, "VERSION_WAIT ms=${SystemClock.elapsedRealtime() - start} revision=${revision ?: "null"}")
            mainHandler.post {
                if (!destroyed && token == navToken) webView.loadUrl(serverUrl)
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

    /** Runs on Chromium IO threads. Null means "let the WebView fetch it". Only the server's own assets. */
    private fun serveFromAssetCache(request: WebResourceRequest): WebResourceResponse? {
        if (cacheDisabled()) return null
        val rev = assetCache.revision ?: return null
        val uri = request.url
        val url = uri.toString()
        if (!Origins.isOn(url, serverOrigin)) return null // never cache or forward cookies to another site
        if (!request.method.equals("GET", ignoreCase = true) || request.requestHeaders.containsKey("Range")) return null
        if (!AssetCachePolicy.shouldCache("GET", uri.path ?: return null)) return null
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

    private fun isCurrent(gen: Int, view: WebView) = !destroyed && gen == loadGeneration && view === webView

    /**
     * Polls for SillyTavern's UI on a server page. Keeps polling after the ready deadline (Stalled),
     * more slowly, so a slow boot still ends up Ready; gives up after [POLL_GIVE_UP_MS].
     */
    private fun startReadyPolling() {
        val gen = loadGeneration
        if (pollingGeneration == gen) return
        pollingGeneration = gen
        val view = webView
        val giveUpAt = SystemClock.elapsedRealtime() + POLL_GIVE_UP_MS
        lateinit var poll: Runnable
        poll = Runnable {
            if (!isCurrent(gen, view)) return@Runnable
            view.evaluateJavascript(READY_PROBE) { r ->
                if (!isCurrent(gen, view)) return@evaluateJavascript
                val st = _state.value
                // Only a page still loading becomes Ready or LoginRequired, never e.g. Unreachable.
                if (st != ChatState.Loading && st != ChatState.Stalled) return@evaluateJavascript
                when (r) {
                    "\"ready\"" -> {
                        val elapsed = SystemClock.elapsedRealtime() - loadStartedAt
                        Log.i(StMobileApp.LOG_TAG, "ST_READY ms=$elapsed")
                        setState(ChatState.Ready)
                        resolveRevisionAfterReady()
                        readTheme(view, gen)
                    }
                    "\"login\"" -> setState(ChatState.LoginRequired)
                    else -> {
                        if (SystemClock.elapsedRealtime() < giveUpAt) {
                            mainHandler.postDelayed(poll, if (st == ChatState.Stalled) STALLED_POLL_MS else POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        mainHandler.post(poll)
    }

    /** Reads ST's theme once the page is Ready (and arms the page's theme watcher). */
    private fun readTheme(view: WebView, gen: Int) {
        view.evaluateJavascript(ThemeBridge.readAndWatchScript()) { raw ->
            if (!isCurrent(gen, view) || raw == null || raw == "null") return@evaluateJavascript
            val vars = try {
                JSONArray("[$raw]").optString(0)
            } catch (_: Exception) {
                null
            }?.let(ThemeBridge::parseMessage) ?: return@evaluateJavascript
            Log.i(StMobileApp.LOG_TAG, "ST_THEME accent=${ThemeBridge.logValue(vars["--SmartThemeQuoteColor"])}")
            onTheme(vars)
        }
    }

    /** The current, loaded page reported a failed request. */
    private fun onPageNetworkFailure(view: WebView) {
        if (destroyed || view !== webView || !_state.value.coversOnConnectionLoss) return
        onNetworkFailure?.invoke()
    }

    /** The page's sheet opened or closed. An open sheet only counts on the current, Ready page. */
    private fun onSheetMessage(view: WebView, open: Boolean) {
        if (destroyed || view !== webView) return
        _sheetOpen.value = open && _state.value == ChatState.Ready
    }

    private fun deliverFiles(uris: List<Uri>) {
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    /** Renderer died. Recreate and reload, at most [MAX_CRASH_RELOADS] times per [CRASH_WINDOW_MS]. */
    private fun onRendererGone(view: WebView, crashed: Boolean) {
        if (view !== webView) { // a popup's renderer
            popups.remove(view)
            destroyView(view)
            return
        }
        val now = SystemClock.elapsedRealtime()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) crashTimes.removeFirst()
        recreate()
        if (crashTimes.size > MAX_CRASH_RELOADS) {
            Log.w(StMobileApp.LOG_TAG, "renderer gone (crashed=$crashed) ${crashTimes.size}x in 60 s; giving up")
            setState(ChatState.Unreachable("The page keeps crashing"))
        } else {
            Log.w(StMobileApp.LOG_TAG, "renderer gone (crashed=$crashed), recreating")
            reload()
        }
    }

    /**
     * target=_blank / window.open. The URL is only known once the new window navigates, so it gets a
     * throwaway WebView that is destroyed as soon as it reveals the URL, when the page closes it, or
     * after [POPUP_TIMEOUT_MS] (window.open() with no URL never navigates). Nothing loads in the main
     * WebView: http(s) goes to the browser like a new Chrome tab, other schemes to their app.
     */
    private fun openNewWindow(resultMsg: Message) {
        val popup = WebView(activity)
        popups += popup
        var done = false
        fun finish() {
            if (done) return
            done = true
            mainHandler.post {
                if (popups.remove(popup)) destroyView(popup)
            }
        }
        fun handle(uri: Uri) {
            if (done || uri.scheme.equals("about", ignoreCase = true)) return // the blank start page
            links.open(uri, LinkPolicy.forNewWindow(uri.toString()))
            finish()
        }
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                handle(request.url)
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                url?.let { handle(Uri.parse(it)) }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone(view, detail.didCrash())
                return true
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = finish()
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
        mainHandler.postDelayed({ finish() }, POPUP_TIMEOUT_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
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
        downloads.install(this, serverOrigin)
        ThemeBridge.install(this, serverOrigin, onTheme)
        SelectSheet.installBridge(this, serverOrigin) { open -> onSheetMessage(this, open) }
        SelectSheet.install(this, serverOrigin, selectSheetScript)
        ChatBreaks.install(this, serverOrigin, chatBreaksScript)
        NetWatch.install(this, serverOrigin, netWatchScript) { onPageNetworkFailure(this) }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor?.invoke(request)

            /** Same-window http(s) stays here (Ruling 10); mailto:, tel:, intent: … go to their app. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val target = LinkPolicy.forMainFrame(request.url.toString()) ?: return false
                links.open(request.url, target)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== webView) return
                loadGeneration++
                _sheetOpen.value = false // a new document has no sheet open
                mainFrameFailed = false
                loadStartedAt = SystemClock.elapsedRealtime()
                onServerPage = Origins.isOn(url, serverOrigin)
                // onReceivedHttpError fires before the navigation commits; take its verdict once.
                val refused = pendingHttpError
                pendingHttpError = null
                when {
                    // Another site (OAuth sign-in): shown as is, no overlay, no readiness polling.
                    !onServerPage -> setState(ChatState.External)
                    refused != null -> {
                        mainFrameFailed = true
                        setState(ChatState.Unreachable(refused))
                    }
                    else -> enterLoading()
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (view !== webView || !request.isForMainFrame || response.statusCode < 400) return
                // Another site's error page is its own business; it shows as External.
                if (!Origins.isOn(request.url.toString(), serverOrigin)) return
                mainFrameFailed = true
                val code = response.statusCode
                val hint = when (code) {
                    403 -> " Check the whitelist."
                    401 -> " Authentication required."
                    else -> ""
                }
                val reason = "Server refused the connection ($code).$hint"
                pendingHttpError = reason
                setState(ChatState.Unreachable(reason))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== webView) return
                CookieManager.getInstance().flush() // persist the login session promptly
                pendingHttpError = null
                if (!mainFrameFailed && onServerPage && Origins.isOn(url, serverOrigin)) startReadyPolling()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view !== webView || !request.isForMainFrame) return
                // Off-origin failures show the WebView's own error page; Back returns to SillyTavern.
                if (!Origins.isOn(request.url.toString(), serverOrigin)) return
                mainFrameFailed = true
                setState(ChatState.Unreachable(error.description?.toString() ?: "Unknown error"))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone(view, detail.didCrash())
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                openNewWindow(resultMsg)
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
                }
                return true
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloads.onDownloadStart(this, url, userAgent, contentDisposition, mimeType)
        }
    }

    /** SAF wants MIME types; ST sends extensions like ".png" too. */
    private fun toMime(t: String): String =
        if (t.startsWith(".")) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) ?: "*/*"
        } else t

    internal companion object {
        /** "ready" needs a positive ST marker; the login page has neither marker. */
        const val READY_PROBE = "(function(){if(location.pathname.indexOf('/login')===0)return 'login';" +
            "return (document.getElementById('preloader')===null&&document.getElementById('send_textarea')!==null)" +
            "?'ready':'wait';})()"
        const val VERSION_TIMEOUT_MS = 5_000
        const val FETCH_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 200L
        const val STALLED_POLL_MS = 1_000L
        const val READY_TIMEOUT_MS = 60_000L
        const val POLL_GIVE_UP_MS = 300_000L
        const val PROBE_BACK_TIMEOUT_MS = 1_000L
        const val POPUP_TIMEOUT_MS = 10_000L
        const val MAX_CRASH_RELOADS = 3
        const val CRASH_WINDOW_MS = 60_000L
        /**
         * Open UI Back can close. The list sheet counts only while open (stm-open): it stays
         * dialog[open] while sliding down, and counting that would make a Back that closed it look
         * unconsumed ("Press back again").
         */
        const val OPEN_UI_SELECTOR = ".openDrawer, dialog[open]:not(#stm-select-sheet), " +
            "#dialogue_popup:not([style*=\"none\"]), #character_popup:not([style*=\"none\"]), " +
            "#select_chat_popup:not([style*=\"none\"]), #stm-select-sheet.stm-open"

        /** The page's bottom minus the top of ST's message box (#form_sheld), in CSS px; -1 without one. */
        const val SEND_FORM_GAP_JS = "(function(){var f=document.getElementById('form_sheld');" +
            "return f?innerHeight-f.getBoundingClientRect().top:-1;})()"

        /** Counts [OPEN_UI_SELECTOR]. */
        const val OPEN_UI_COUNT_JS = "document.querySelectorAll('$OPEN_UI_SELECTOR').length"

        /**
         * Counts open UI, then sends `cancel` to the topmost open dialog (a sheet already sliding
         * down is skipped, so a quick second Back reaches what is under it) or else Escape to the
         * focused element. Evaluates to the count before.
         */
        const val BACK_PROBE_JS = "(function(){var n=$OPEN_UI_COUNT_JS;" +
            "var d=document.querySelectorAll('dialog[open]:not(#stm-select-sheet:not(.stm-open))'),t=d[d.length-1];" +
            "if(t){t.dispatchEvent(new Event('cancel',{cancelable:true}));}" +
            "else{(document.activeElement||document).dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));}" +
            "return n;})()"
    }
}
