package dev.halftoast.stmobile

import android.content.Context
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import dev.halftoast.stmobile.chat.BackAction
import dev.halftoast.stmobile.chat.BackPressPolicy
import dev.halftoast.stmobile.chat.ChatScreen
import dev.halftoast.stmobile.chat.WebViewHolder
import dev.halftoast.stmobile.settings.AppSettings

enum class Tab(val label: String) {
    Chat("Chat"),
    Panel("Panel"),
    Stats("Stats"),
    Logs("Logs"),
}

class MainActivity : ComponentActivity() {
    private lateinit var holder: WebViewHolder
    private var lastUnconsumedBackAtMs: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(getSharedPreferences("settings", Context.MODE_PRIVATE))
        holder = WebViewHolder(this, settings)
        setContent {
            MaterialTheme {
                var selected by rememberSaveable { mutableStateOf(Tab.Chat) }
                BackHandler {
                    if (selected != Tab.Chat) selected = Tab.Chat else handleChatBack()
                }
                Scaffold(
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
                    if (selected == Tab.Chat) {
                        ChatScreen(
                            holder = holder,
                            onOpenControlCenter = { selected = Tab.Panel },
                            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text("${selected.label} (placeholder)")
                        }
                    }
                }
            }
        }
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
