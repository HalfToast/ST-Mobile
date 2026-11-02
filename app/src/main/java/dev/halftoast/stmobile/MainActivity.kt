package dev.halftoast.stmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch
import dev.halftoast.stmobile.chat.ChatScreen
import dev.halftoast.stmobile.chat.ChatState
import dev.halftoast.stmobile.chat.ConnectScreen
import dev.halftoast.stmobile.chat.ConnectionMonitor
import dev.halftoast.stmobile.chat.Snapshot
import dev.halftoast.stmobile.chat.UnreachableActions
import dev.halftoast.stmobile.chat.UnreachableProbe
import dev.halftoast.stmobile.chat.UnreachableScreen
import dev.halftoast.stmobile.chat.WebViewHolder
import dev.halftoast.stmobile.chat.backProbesPage
import dev.halftoast.stmobile.chat.isLiveServerPage
import dev.halftoast.stmobile.chat.isTailscaleServer
import dev.halftoast.stmobile.chat.reloadWhenServerBack
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.logs.LogsScreen
import dev.halftoast.stmobile.nav.BackMenu
import dev.halftoast.stmobile.nav.MenuSpot
import dev.halftoast.stmobile.nav.menuBottomGapDp
import dev.halftoast.stmobile.nav.menuEdge
import dev.halftoast.stmobile.nav.chatSettled
import dev.halftoast.stmobile.nav.slideAnimates
import dev.halftoast.stmobile.nav.icon
import dev.halftoast.stmobile.panel.AndroidProbeSource
import dev.halftoast.stmobile.panel.PanelAction
import dev.halftoast.stmobile.panel.PanelScreen
import dev.halftoast.stmobile.panel.PanelViewModel
import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.settings.ConnectionForm
import dev.halftoast.stmobile.settings.SettingsScreen
import dev.halftoast.stmobile.settings.SetupScreen
import dev.halftoast.stmobile.settings.sshScreensReady
import dev.halftoast.stmobile.stats.StatsScreen
import dev.halftoast.stmobile.stats.StatsViewModel
import dev.halftoast.stmobile.ui.AnimMs
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StMobileTheme
import dev.halftoast.stmobile.ui.StTopBar
import dev.halftoast.stmobile.ui.isLightBackground
import dev.halftoast.stmobile.ui.palette

enum class Screen(val title: String) {
    Chat("Chat"),
    Panel("Control Center"),
    Stats("Stats"),
    Logs("Logs"),
}

/** Full-screen pages shown in place of the current [Screen]. */
enum class Overlay { None, Settings, Setup }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var holder: WebViewHolder
    /** Covers the chat when the connection drops under it, and brings it back. Foreground only. */
    private lateinit var connection: ConnectionMonitor
    private var chatTabShown = true
    /** Where the back menu is open; null while closed. */
    private var backMenu by mutableStateOf<MenuSpot?>(null)
    /** The side the last back gesture came from ([androidx.activity.BackEventCompat.swipeEdge]). */
    private var lastBackEdge: Int? = null
    /** Quit was confirmed: the process ends once this Activity is destroyed (after onStop's snapshot). */
    private var quitting = false

    /**
     * Activity-scoped so actions outlive tab switches. It can outlive this Activity instance, so its
     * callbacks reach the current WebViewHolder through [currentHolder] instead of capturing [holder].
     */
    private val panelVm: PanelViewModel by viewModels {
        val app = application as StMobileApp
        viewModelFactory {
            initializer {
                PanelViewModel(
                    shell = app.remoteShell,
                    probes = AndroidProbeSource(app, app.settings),
                    actions = app.actions,
                    // Never reload a working or loading page, or a sign-in on another site: only
                    // Unreachable / Stalled (a reload would kill a generation in progress).
                    onServerRestarted = {
                        // Also a chat covered because the connection was lost: its session belongs to
                        // the old process. No double reload: whichever runs first makes it Loading.
                        currentHolder.get()?.let { h -> if (h.state.value.reloadWhenServerBack || h.cover.value != null) h.reload() }
                    },
                    onServerDown = { reason -> currentHolder.get()?.markServerDown(reason) },
                )
            }
        }
    }

    private val statsVm: StatsViewModel by viewModels {
        val app = application as StMobileApp
        viewModelFactory {
            initializer {
                StatsViewModel(app.remoteShell) { Probes.stats(app, app.settings.stDir, app.settings.stUserDir) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = application as StMobileApp
        val settings = app.settings
        holder = WebViewHolder(this, settings, app.themeStore::update)
        currentHolder = WeakReference(holder)
        connection = ConnectionMonitor(holder, unreachableProbe, { settings.serverUrl }, lifecycleScope)
        if (settings.keepAliveEnabled) KeepAliveService.start(this) else KeepAliveService.stop(this)
        // Chat works without SSH setup, which is where these are asked otherwise: ask once here too.
        if (settings.keepAliveEnabled && !settings.keepAlivePermissionsAsked) {
            settings.keepAlivePermissionsAsked = true
            KeepAliveService.requestKeepAlivePermissions(this)
        }
        lifecycleScope.launch {
            holder.state.collect {
                if (it == ChatState.LoginRequired) Snapshot.clear(applicationContext)
                // A live page (chat or login) means the server is back for Chat; tell the Panel.
                // Not External (another site says nothing about the server) and not Stalled.
                if (it.isLiveServerPage) panelVm.onChatReady()
            }
        }
        applySystemBars(isLightBackground(app.themeStore.theme.value.palette().background))
        setContent {
            val theme by app.themeStore.theme.collectAsState()
            StMobileTheme(theme) {
                val palette = LocalStPalette.current
                // Status and gesture bar icons stay readable on a light ST theme too.
                val lightBars = isLightBackground(palette.background)
                LaunchedEffect(lightBars) { applySystemBars(lightBars) }
                var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
                var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
                // Chat works without SSH; Panel, Stats and Logs show Setup until it's done, and again
                // if SSH is cleared in Settings afterwards.
                var setupDone by rememberSaveable { mutableStateOf(settings.setupComplete) }
                var sshSetUp by remember { mutableStateOf(settings.hasSsh) }
                // Chat shows the Connect screen until a SillyTavern address is saved.
                var serverUrlSaved by remember { mutableStateOf(settings.hasServerUrl) }
                val rereadSettings = {
                    sshSetUp = settings.hasSsh
                    serverUrlSaved = settings.hasServerUrl
                }
                // Settings and Re-run setup save both; read them again whenever one of them closes.
                LaunchedEffect(overlay) { rereadSettings() }
                val imeTarget = WindowInsets.imeAnimationTarget
                val density = LocalDensity.current
                // The native screen drawn over Chat (sliding in, shown, or sliding out); null once Chat
                // has settled. Chat stays composed under it until it fully covers Chat.
                var covering by remember { mutableStateOf(screen.takeIf { it != Screen.Chat }) }
                var chatComposed by remember { mutableStateOf(screen == Screen.Chat) }
                val slide = remember { Animatable(if (screen == Screen.Chat) 0f else 1f) }
                val ime = WindowInsets.ime
                LaunchedEffect(screen) {
                    val spec = tween<Float>(AnimMs, easing = FastOutSlowInEasing)
                    val animate = slideAnimates(ime.getBottom(density), imeTarget.getBottom(density))
                    if (screen != Screen.Chat) {
                        covering = screen
                        if (animate) slide.animateTo(1f, spec) else slide.snapTo(1f)
                        chatComposed = false // covered: the WebView leaves the window
                    } else {
                        chatComposed = true
                        withFrameNanos { } // Chat (and the WebView) is back before the screen slides away
                        if (animate) slide.animateTo(0f, spec) else slide.snapTo(0f)
                        covering = null
                    }
                }
                SideEffect { chatTabShown = chatSettled(screen, overlay, covering) }
                val unreachableActions = remember {
                    UnreachableActions(
                        classify = { unreachableProbe.classify(settings.serverUrl) },
                        tailscaleHost = { isTailscaleServer(settings.serverUrl) },
                        vpnUp = unreachableProbe::vpnUp,
                        watchVpn = unreachableProbe::watchVpn,
                        onRetry = holder::retry,
                        onOpenControlCenter = { screen = Screen.Panel },
                        onOpenTailscale = ::openTailscale,
                        // The Control Center's own Start; Chat then reloads once the server serves again,
                        // as after a Start there. Without SSH set up, the Control Center shows Setup.
                        onStartServer = {
                            if (sshScreensReady(setupDone, sshSetUp)) {
                                panelVm.request(PanelAction.Start)
                                // request() ignores a Start while something runs; run() marks it busy at once.
                                panelVm.ui.value.busy == PanelAction.Start
                            } else {
                                screen = Screen.Panel
                                false
                            }
                        },
                    )
                }
                // The same buttons over a chat whose connection dropped; Retry goes to the monitor.
                val coverActions = remember {
                    UnreachableActions(
                        classify = unreachableActions.classify,
                        tailscaleHost = unreachableActions.tailscaleHost,
                        vpnUp = unreachableActions.vpnUp,
                        watchVpn = unreachableActions.watchVpn,
                        onRetry = connection::retry,
                        onOpenControlCenter = unreachableActions.onOpenControlCenter,
                        onOpenTailscale = unreachableActions.onOpenTailscale,
                        onStartServer = unreachableActions.onStartServer,
                    )
                }
                PredictiveBackHandler { gesture ->
                    var edge: Int? = null
                    // Throws if the gesture is cancelled: nothing happens then.
                    gesture.collect { edge = it.swipeEdge }
                    lastBackEdge = edge
                    when {
                        overlay == Overlay.Setup -> overlay = Overlay.Settings
                        overlay == Overlay.Settings -> overlay = Overlay.None
                        screen != Screen.Chat -> screen = Screen.Chat
                        else -> handleChatBack()
                    }
                }
                when (overlay) {
                    Overlay.Settings -> {
                        SettingsScreen(
                            settings = settings,
                            shell = app.remoteShell,
                            onClose = { overlay = Overlay.None },
                            onRerunSetup = { overlay = Overlay.Setup },
                            onServerUrlChanged = { holder.onServerUrlChanged() },
                        )
                    }
                    Overlay.Setup -> {
                        Scaffold(
                            containerColor = Color(palette.background),
                            topBar = {
                                Box(Modifier.background(Color(palette.background)).windowInsetsPadding(WindowInsets.statusBars)) {
                                    StTopBar("Re-run setup", onBack = { overlay = Overlay.Settings })
                                }
                            },
                        ) { padding ->
                            SetupScreen(
                                settings = settings,
                                keys = app.keyManager,
                                shell = app.remoteShell,
                                onComplete = { urlChanged ->
                                    setupDone = true
                                    rereadSettings()
                                    overlay = Overlay.Settings
                                    if (urlChanged) holder.onServerUrlChanged()
                                },
                                onCancel = { overlay = Overlay.Settings },
                                allowTrust = true,
                                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                            )
                        }
                    }
                    Overlay.None -> Box(Modifier.fillMaxSize()) {
                        if (chatComposed) {
                            // The dark background is what shows while the WebView already has its final
                            // height and the keyboard is still sliding (no light strip).
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(palette.background)),
                            ) {
                                // Jump straight to the keyboard's final height: following the slide frame
                                // by frame resizes the WebView, and so re-lays-out SillyTavern, every frame.
                                val chatInsets = Modifier
                                    .windowInsetsPadding(WindowInsets.systemBars)
                                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
                                if (!serverUrlSaved) {
                                    // Nothing is loaded until then (WebViewHolder loads nothing without an address).
                                    ConnectScreen(
                                        initialUrl = settings.serverUrl,
                                        onConnect = { url ->
                                            val changed = ConnectionForm.from(settings).copy(serverUrl = url).saveServerUrl(settings)
                                            serverUrlSaved = settings.hasServerUrl
                                            if (changed) holder.onServerUrlChanged()
                                        },
                                        modifier = chatInsets,
                                    )
                                } else ChatScreen(
                                    holder = holder,
                                    unreachable = { s ->
                                        // Collected here only: dashboard updates never recompose the chat.
                                        val panel by panelVm.ui.collectAsState()
                                        val attempt by holder.loadAttempts.collectAsState()
                                        UnreachableScreen(
                                            state = s,
                                            attempt = attempt,
                                            actions = unreachableActions,
                                            // A Restart from the Control Center also shows Unreachable until it serves.
                                            starting = panel.busy == PanelAction.Start || panel.busy == PanelAction.Restart,
                                            // Same gate as the Control Center's Start.
                                            serverOnline = panel.dashboard?.online == true,
                                            lastAction = panel.lastAction,
                                        )
                                    },
                                    covered = { case ->
                                        val panel by panelVm.ui.collectAsState()
                                        val checking by connection.checking.collectAsState()
                                        UnreachableScreen(
                                            state = ChatState.Unreachable("The connection to SillyTavern was lost."),
                                            attempt = 0,
                                            actions = coverActions,
                                            starting = panel.busy == PanelAction.Start || panel.busy == PanelAction.Restart,
                                            serverOnline = panel.dashboard?.online == true,
                                            lastAction = panel.lastAction,
                                            fixedCase = case,
                                            retrying = checking,
                                        )
                                    },
                                    modifier = chatInsets,
                                )
                                backMenu?.let { spot ->
                                    BackMenu(
                                        spot = spot,
                                        onDismiss = { backMenu = null },
                                        onOpen = { screen = it },
                                        // The user asked for it: no "never reload a working chat" guard here.
                                        onReload = { holder.reload() },
                                        onQuit = ::quit,
                                        // Debug only: long-press Reload kills the renderer to test recovery.
                                        debugLongPress = if (BuildConfig.DEBUG) {
                                            {
                                                Log.i(StMobileApp.LOG_TAG, "debug: killing renderer")
                                                holder.webView.loadUrl("chrome://kill")
                                            }
                                        } else null,
                                    )
                                }
                            }
                        }
                        covering?.let { shown ->
                            NativeScreen(
                                shown = shown,
                                sshReady = sshScreensReady(setupDone, sshSetUp),
                                onSetupDone = {
                                    setupDone = true
                                    rereadSettings()
                                },
                                onNavigate = { screen = it },
                                onOpenSettings = { overlay = Overlay.Settings },
                                // Only this layer moves; Chat underneath stays put.
                                modifier = Modifier.graphicsLayer { translationX = (1f - slide.value) * size.width },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Control Center, Stats or Logs ([shown]) full screen under its top bar; Setup until [sshReady]. */
    @Composable
    private fun NativeScreen(
        shown: Screen,
        sshReady: Boolean,
        onSetupDone: () -> Unit,
        onNavigate: (Screen) -> Unit,
        onOpenSettings: () -> Unit,
        modifier: Modifier,
    ) {
        val app = application as StMobileApp
        val palette = LocalStPalette.current
        Surface(modifier.fillMaxSize(), color = Color(palette.background)) {
            Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                StTopBar(shown.title, onBack = { onNavigate(Screen.Chat) }) {
                    Screen.entries.filter { it != Screen.Chat && it != shown }.forEach { other ->
                        IconButton(onClick = { onNavigate(other) }) {
                            Icon(other.icon, contentDescription = other.title, Modifier.size(20.dp), tint = Color(palette.muted))
                        }
                    }
                    if (shown == Screen.Panel) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", Modifier.size(20.dp), tint = Color(palette.muted))
                        }
                    }
                }
                when {
                    !sshReady -> SetupScreen(
                        settings = app.settings,
                        keys = app.keyManager,
                        shell = app.remoteShell,
                        onComplete = { urlChanged ->
                            onSetupDone()
                            if (urlChanged) holder.onServerUrlChanged()
                        },
                        onOpenSettings = onOpenSettings,
                    )
                    shown == Screen.Panel -> PanelScreen(
                        vm = panelVm,
                        onOpenSettings = onOpenSettings,
                        onOpenLogs = { onNavigate(Screen.Logs) },
                    )
                    shown == Screen.Stats -> StatsScreen(
                        vm = statsVm,
                        onOpenSettings = onOpenSettings,
                    )
                    else -> LogsScreen(
                        shell = app.remoteShell,
                        stDir = { app.settings.stDir },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }

    private val unreachableProbe by lazy { UnreachableProbe(this) }

    /** Tailscale's own app, or its Play Store page if it isn't installed. */
    private fun openTailscale() {
        val launch = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        val intents = listOfNotNull(
            launch,
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    /** Edge-to-edge with transparent bars; dark icons over a [light] background, light ones otherwise. */
    private fun applySystemBars(light: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (light) SystemBarStyle.light(clear, clear) else SystemBarStyle.dark(clear)
        enableEdgeToEdge(style, style)
    }

    /** Closes the app for good: the keep-alive notification, this task, then the process. */
    private fun quit() {
        quitting = true
        KeepAliveService.stop(this)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (quitting) Process.killProcess(Process.myPid())
    }

    override fun onStart() {
        super.onStart()
        panelVm.setAppForeground(true)
        connection.start()
    }

    override fun onStop() {
        panelVm.setAppForeground(false)
        connection.stop()
        // Not with a list sheet open: the next launch would show a picture of the sheet over the chat.
        // Nor while the connection is lost: the page under the cover may already be broken.
        if (chatTabShown && holder.state.value == ChatState.Ready && !holder.sheetOpen.value && holder.cover.value == null) {
            Snapshot.save(this, holder.webView)
        }
        super.onStop()
    }

    companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

        /** The live Activity's holder, for [panelVm]'s callbacks. Weak: never keeps an Activity alive. */
        private var currentHolder = WeakReference<WebViewHolder>(null)
    }

    /**
     * SillyTavern's UI is what the user sees. Not on the Connect screen (no address: nothing loaded),
     * nor while Unreachable, Loading, Stalled, External, or covered (connection lost).
     */
    private fun stPageShown(): Boolean =
        (application as StMobileApp).settings.hasServerUrl && holder.state.value.backProbesPage && holder.cover.value == null

    private fun handleChatBack() {
        // The Connect screen: no page to step back in or poke; back opens the menu.
        if (!(application as StMobileApp).settings.hasServerUrl) return onBackDecided(consumed = false)
        if (holder.goBackIfOffOrigin()) return
        // The page is hidden or isn't SillyTavern's UI: don't poke it; back opens the menu.
        if (!stPageShown()) return onBackDecided(consumed = false)
        holder.probeBack(::onBackDecided)
    }

    /**
     * SillyTavern closed something with that back ([consumed]); otherwise back opens the menu, just
     * above ST's message box when the page is ST's UI.
     */
    private fun onBackDecided(consumed: Boolean) {
        if (consumed) return
        val edge = menuEdge(lastBackEdge)
        if (!stPageShown()) {
            backMenu = MenuSpot(edge, menuBottomGapDp(null))
            return
        }
        holder.measureSendForm { gap -> backMenu = MenuSpot(edge, menuBottomGapDp(gap)) }
    }
}
