package dev.halftoast.stmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch
import dev.halftoast.stmobile.chat.BackAction
import dev.halftoast.stmobile.chat.BackPressPolicy
import dev.halftoast.stmobile.chat.ChatScreen
import dev.halftoast.stmobile.chat.ChatState
import dev.halftoast.stmobile.chat.ConnectionMonitor
import dev.halftoast.stmobile.chat.Snapshot
import dev.halftoast.stmobile.chat.UnreachableActions
import dev.halftoast.stmobile.chat.UnreachableProbe
import dev.halftoast.stmobile.chat.UnreachableScreen
import dev.halftoast.stmobile.chat.WebViewHolder
import dev.halftoast.stmobile.chat.backProbesPage
import dev.halftoast.stmobile.chat.isLiveServerPage
import dev.halftoast.stmobile.chat.reloadWhenServerBack
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.logs.LogsScreen
import dev.halftoast.stmobile.nav.FloatingNav
import dev.halftoast.stmobile.nav.chatSettled
import dev.halftoast.stmobile.nav.navHidden
import dev.halftoast.stmobile.nav.slideAnimates
import dev.halftoast.stmobile.nav.icon
import dev.halftoast.stmobile.panel.AndroidProbeSource
import dev.halftoast.stmobile.panel.PanelAction
import dev.halftoast.stmobile.panel.PanelScreen
import dev.halftoast.stmobile.panel.PanelViewModel
import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.settings.SettingsScreen
import dev.halftoast.stmobile.settings.SetupScreen
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
    private var lastUnconsumedBackAtMs: Long? = null

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
                StatsViewModel(app.remoteShell) { Probes.stats(app, app.settings.stUserDir) }
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
                // Chat works without SSH; Panel, Stats and Logs show Setup until it's done.
                var setupDone by rememberSaveable { mutableStateOf(settings.setupComplete) }
                var fab by remember { mutableStateOf(settings.fabPosition) }
                // Read only inside FloatingNav's placement and event code, never during composition.
                var chatSize by remember { mutableStateOf(IntSize.Zero) }
                val imeTarget = WindowInsets.imeAnimationTarget
                val sheetOpen = holder.sheetOpen.collectAsState()
                val chatState = holder.state.collectAsState()
                val chatCover = holder.cover.collectAsState()
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
                        vpnUp = unreachableProbe::vpnUp,
                        watchVpn = unreachableProbe::watchVpn,
                        onRetry = holder::retry,
                        onOpenControlCenter = { screen = Screen.Panel },
                        onOpenTailscale = ::openTailscale,
                        // The Control Center's own Start; Chat then recovers the way it does after a
                        // Start there (Ruling 18). Without SSH set up, the Control Center shows Setup.
                        onStartServer = {
                            if (setupDone) {
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
                        vpnUp = unreachableActions.vpnUp,
                        watchVpn = unreachableActions.watchVpn,
                        onRetry = connection::retry,
                        onOpenControlCenter = unreachableActions.onOpenControlCenter,
                        onOpenTailscale = unreachableActions.onOpenTailscale,
                        onStartServer = unreachableActions.onStartServer,
                    )
                }
                BackHandler {
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
                                    .background(Color(palette.background))
                                    .onSizeChanged { chatSize = it },
                            ) {
                                ChatScreen(
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
                                    // Jump straight to the keyboard's final height: following the slide frame
                                    // by frame resizes the WebView, and so re-lays-out SillyTavern, every frame.
                                    modifier = Modifier
                                        .windowInsetsPadding(WindowInsets.systemBars)
                                        .windowInsetsPadding(WindowInsets.imeAnimationTarget),
                                )
                                FloatingNav(
                                    position = fab,
                                    containerSize = { chatSize },
                                    hidden = {
                                        navHidden(
                                            imeTarget.getBottom(density),
                                            sheetOpen.value,
                                            chatUnreachable = chatState.value is ChatState.Unreachable || chatCover.value != null,
                                        )
                                    },
                                    onMoved = {
                                        fab = it
                                        settings.fabPosition = it
                                    },
                                    onOpen = { screen = it },
                                    // The user asked for it: no "never reload a working chat" guard here.
                                    onRefresh = { holder.reload() },
                                    // Debug only: long-press kills the renderer to test recovery.
                                    debugLongPress = if (BuildConfig.DEBUG) {
                                        {
                                            Log.i(StMobileApp.LOG_TAG, "debug: killing renderer")
                                            holder.webView.loadUrl("chrome://kill")
                                        }
                                    } else null,
                                )
                            }
                        }
                        covering?.let { shown ->
                            NativeScreen(
                                shown = shown,
                                setupDone = setupDone,
                                onSetupDone = { setupDone = true },
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

    /** Control Center, Stats or Logs ([shown]) full screen under its top bar; Setup until that's done. */
    @Composable
    private fun NativeScreen(
        shown: Screen,
        setupDone: Boolean,
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
                    !setupDone -> SetupScreen(
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

    private fun handleChatBack() {
        if (holder.goBackIfOffOrigin()) return
        // Unreachable, Loading, Stalled, External: the page is hidden or isn't SillyTavern's UI, so
        // don't poke it; plain back (history above, then "press again").
        // Covered (connection lost) or not ST's UI: plain back, the page is not poked.
        if (!holder.state.value.backProbesPage || holder.cover.value != null) return onBackDecided(consumed = false)
        holder.probeBack(::onBackDecided)
    }

    private fun onBackDecided(consumed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        when (BackPressPolicy.decide(consumed, lastUnconsumedBackAtMs, now)) {
            BackAction.ConsumedByPage -> lastUnconsumedBackAtMs = null
            BackAction.ShowPressAgainHint -> {
                lastUnconsumedBackAtMs = now
                Toast.makeText(this, "Press back again to leave", Toast.LENGTH_SHORT).show()
            }
            BackAction.MoveTaskToBack -> {
                lastUnconsumedBackAtMs = null
                moveTaskToBack(true)
            }
        }
    }
}
