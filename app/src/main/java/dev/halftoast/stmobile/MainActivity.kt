package dev.halftoast.stmobile

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.halftoast.stmobile.chat.Snapshot
import dev.halftoast.stmobile.chat.WebViewHolder
import dev.halftoast.stmobile.chat.backProbesPage
import dev.halftoast.stmobile.chat.isLiveServerPage
import dev.halftoast.stmobile.chat.reloadWhenServerBack
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.logs.LogsScreen
import dev.halftoast.stmobile.nav.FloatingNav
import dev.halftoast.stmobile.nav.icon
import dev.halftoast.stmobile.panel.AndroidProbeSource
import dev.halftoast.stmobile.panel.PanelScreen
import dev.halftoast.stmobile.panel.PanelViewModel
import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.settings.SettingsScreen
import dev.halftoast.stmobile.settings.SetupScreen
import dev.halftoast.stmobile.stats.StatsScreen
import dev.halftoast.stmobile.stats.StatsViewModel
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StMobileTheme
import dev.halftoast.stmobile.ui.StTopBar

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
                        currentHolder.get()?.let { h -> if (h.state.value.reloadWhenServerBack) h.reload() }
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
        if (settings.keepAliveEnabled) KeepAliveService.start(this) else KeepAliveService.stop(this)
        lifecycleScope.launch {
            holder.state.collect {
                if (it == ChatState.LoginRequired) Snapshot.clear(applicationContext)
                // A live page (chat or login) means the server is back for Chat; tell the Panel.
                // Not External (another site says nothing about the server) and not Stalled.
                if (it.isLiveServerPage) panelVm.onChatReady()
            }
        }
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val theme by app.themeStore.theme.collectAsState()
            StMobileTheme(theme) {
                val palette = LocalStPalette.current
                var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
                var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
                // Chat works without SSH; Panel, Stats and Logs show Setup until it's done.
                var setupDone by rememberSaveable { mutableStateOf(settings.setupComplete) }
                var fab by remember { mutableStateOf(settings.fabPosition) }
                SideEffect { chatTabShown = screen == Screen.Chat && overlay == Overlay.None }
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
                    Overlay.None -> if (screen == Screen.Chat) {
                        // The dark background is what shows while the WebView already has its final
                        // height and the keyboard is still sliding (no light strip).
                        Box(Modifier.fillMaxSize().background(Color(palette.background))) {
                            ChatScreen(
                                holder = holder,
                                onOpenControlCenter = { screen = Screen.Panel },
                                // Jump straight to the keyboard's final height: following the slide frame
                                // by frame resizes the WebView, and so re-lays-out SillyTavern, every frame.
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.systemBars)
                                    .windowInsetsPadding(WindowInsets.imeAnimationTarget),
                            )
                            FloatingNav(
                                position = fab,
                                onMoved = {
                                    fab = it
                                    settings.fabPosition = it
                                },
                                onOpen = { screen = it },
                                // Debug only: long-press kills the renderer to test recovery.
                                debugLongPress = if (BuildConfig.DEBUG) {
                                    {
                                        Log.i(StMobileApp.LOG_TAG, "debug: killing renderer")
                                        holder.webView.loadUrl("chrome://kill")
                                    }
                                } else null,
                            )
                        }
                    } else Surface(Modifier.fillMaxSize(), color = Color(palette.background)) {
                        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                            StTopBar(screen.title, onBack = { screen = Screen.Chat }) {
                                Screen.entries.filter { it != Screen.Chat && it != screen }.forEach { other ->
                                    IconButton(onClick = { screen = other }) {
                                        Icon(other.icon, contentDescription = other.title, Modifier.size(20.dp), tint = Color(palette.muted))
                                    }
                                }
                                if (screen == Screen.Panel) {
                                    IconButton(onClick = { overlay = Overlay.Settings }) {
                                        Icon(Icons.Filled.Settings, contentDescription = "Settings", Modifier.size(20.dp), tint = Color(palette.muted))
                                    }
                                }
                            }
                            when {
                                !setupDone -> SetupScreen(
                                    settings = settings,
                                    keys = app.keyManager,
                                    shell = app.remoteShell,
                                    onComplete = { urlChanged ->
                                        setupDone = true
                                        if (urlChanged) holder.onServerUrlChanged()
                                    },
                                    onOpenSettings = { overlay = Overlay.Settings },
                                )
                                screen == Screen.Panel -> PanelScreen(
                                    vm = panelVm,
                                    onOpenSettings = { overlay = Overlay.Settings },
                                    onOpenLogs = { screen = Screen.Logs },
                                )
                                screen == Screen.Stats -> StatsScreen(
                                    vm = statsVm,
                                    onOpenSettings = { overlay = Overlay.Settings },
                                )
                                else -> LogsScreen(
                                    shell = app.remoteShell,
                                    onOpenSettings = { overlay = Overlay.Settings },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        panelVm.setAppForeground(true)
    }

    override fun onStop() {
        panelVm.setAppForeground(false)
        if (chatTabShown && holder.state.value == ChatState.Ready) {
            Snapshot.save(this, holder.webView)
        }
        super.onStop()
    }

    companion object {
        /** The live Activity's holder, for [panelVm]'s callbacks. Weak: never keeps an Activity alive. */
        private var currentHolder = WeakReference<WebViewHolder>(null)
    }

    private fun handleChatBack() {
        if (holder.goBackIfOffOrigin()) return
        // Unreachable, Loading, Stalled, External: the page is hidden or isn't SillyTavern's UI, so
        // don't poke it; plain back (history above, then "press again").
        if (!holder.state.value.backProbesPage) return onBackDecided(consumed = false)
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
