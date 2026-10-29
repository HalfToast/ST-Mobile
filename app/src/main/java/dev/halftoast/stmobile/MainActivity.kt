package dev.halftoast.stmobile

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.halftoast.stmobile.chat.BackAction
import dev.halftoast.stmobile.chat.BackPressPolicy
import dev.halftoast.stmobile.chat.ChatScreen
import dev.halftoast.stmobile.chat.ChatState
import dev.halftoast.stmobile.chat.Snapshot
import dev.halftoast.stmobile.chat.WebViewHolder
import dev.halftoast.stmobile.keepalive.KeepAliveService
import dev.halftoast.stmobile.panel.AndroidProbeSource
import dev.halftoast.stmobile.panel.PanelScreen
import dev.halftoast.stmobile.panel.PanelViewModel
import dev.halftoast.stmobile.settings.SettingsScreen
import dev.halftoast.stmobile.settings.SetupScreen

enum class Tab(val label: String) {
    Chat("Chat"),
    Panel("Panel"),
    Stats("Stats"),
    Logs("Logs"),
}

/** Full-screen pages shown over the tabs. */
enum class Overlay { None, Settings, Setup }

@OptIn(ExperimentalMaterial3Api::class)
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
                    onUpdated = { currentHolder.get()?.revalidate() },
                    onServerRestarted = { currentHolder.get()?.reload() },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as StMobileApp
        val settings = app.settings
        holder = WebViewHolder(this, settings)
        currentHolder = WeakReference(holder)
        if (settings.keepAliveEnabled) KeepAliveService.start(this) else KeepAliveService.stop(this)
        lifecycleScope.launch {
            holder.state.collect { if (it == ChatState.LoginRequired) Snapshot.clear(applicationContext) }
        }
        setContent {
            MaterialTheme {
                var selected by rememberSaveable { mutableStateOf(Tab.Chat) }
                var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
                // Chat works without SSH; Panel, Stats and Logs show Setup until it's done.
                var setupDone by rememberSaveable { mutableStateOf(settings.setupComplete) }
                SideEffect { chatTabShown = selected == Tab.Chat && overlay == Overlay.None }
                BackHandler {
                    when {
                        overlay == Overlay.Setup -> overlay = Overlay.Settings
                        overlay == Overlay.Settings -> overlay = Overlay.None
                        selected != Tab.Chat -> selected = Tab.Chat
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
                            onServerUrlChanged = { holder.reload() },
                        )
                    }
                    Overlay.Setup -> {
                        Scaffold(topBar = {
                            TopAppBar(
                                title = { Text("Re-run setup") },
                                navigationIcon = {
                                    IconButton(onClick = { overlay = Overlay.Settings }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                },
                            )
                        }) { padding ->
                            SetupScreen(
                                settings = settings,
                                keys = app.keyManager,
                                shell = app.remoteShell,
                                onComplete = { urlChanged ->
                                    setupDone = true
                                    overlay = Overlay.Settings
                                    if (urlChanged) holder.reload()
                                },
                                onCancel = { overlay = Overlay.Settings },
                                allowTrust = true,
                                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                            )
                        }
                    }
                    Overlay.None -> Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            NavigationBar {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = tab == selected,
                                        onClick = { selected = tab },
                                        modifier = if (BuildConfig.DEBUG && tab == Tab.Chat) {
                                            // Debug only: long-press kills the renderer to test recovery.
                                            Modifier.pointerInput(Unit) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                    val up = withTimeoutOrNull(800) {
                                                        waitForUpOrCancellation(PointerEventPass.Initial)
                                                    }
                                                    if (up == null) {
                                                        Log.i(StMobileApp.LOG_TAG, "debug: killing renderer")
                                                        holder.webView.loadUrl("chrome://kill")
                                                    }
                                                }
                                            }
                                        } else Modifier,
                                        icon = {},
                                        label = { Text(tab.label) },
                                        alwaysShowLabel = true,
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        val content = Modifier.padding(padding).consumeWindowInsets(padding)
                        when {
                            selected == Tab.Chat -> ChatScreen(
                                holder = holder,
                                onOpenControlCenter = { selected = Tab.Panel },
                                modifier = content.imePadding(),
                            )
                            !setupDone -> SetupScreen(
                                settings = settings,
                                keys = app.keyManager,
                                shell = app.remoteShell,
                                onComplete = { urlChanged ->
                                    setupDone = true
                                    if (urlChanged) holder.reload()
                                },
                                onOpenSettings = { overlay = Overlay.Settings },
                                modifier = content,
                            )
                            selected == Tab.Panel -> Column(content) {
                                TopAppBar(
                                    title = { Text("Control Center") },
                                    windowInsets = WindowInsets(0),
                                    actions = {
                                        IconButton(onClick = { overlay = Overlay.Settings }) {
                                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                        }
                                    },
                                )
                                PanelScreen(
                                    vm = panelVm,
                                    onOpenSettings = { overlay = Overlay.Settings },
                                    onOpenLogs = { selected = Tab.Logs },
                                )
                            }
                            else -> Box(content) { Placeholder(selected) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Placeholder(tab: Tab) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("${tab.label} (placeholder)")
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
        holder.probeBack { consumed ->
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
}
