package dev.halftoast.stmobile.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.panel.ActionResult
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Unreachable screen can do; wired by MainActivity. */
class UnreachableActions(
    /** Works out the [UnreachableCase] (off the main thread). */
    val classify: suspend () -> UnreachableCase,
    /** Watches for a VPN coming up; returns the unregister function. */
    val watchVpn: (onVpnUp: () -> Unit) -> () -> Unit,
    val onRetry: () -> Unit,
    val onOpenControlCenter: () -> Unit,
    val onOpenTailscale: () -> Unit,
    /** The Control Center's Start (or Setup, if SSH isn't set up yet). */
    val onStartServer: () -> Unit,
)

/**
 * Chat can't reach SillyTavern. The screen says why, as far as the phone can tell (classified each
 * time it shows, so again after Retry), and offers what helps: open Tailscale, start the server, or
 * the Control Center. While the case is being worked out, and when the server's port answers, it
 * shows the plain [state] reason. With Tailscale off it retries once by itself when a VPN comes up.
 */
@Composable
fun UnreachableScreen(
    state: ChatState.Unreachable,
    actions: UnreachableActions,
    starting: Boolean,
    lastAction: ActionResult?,
) {
    val p = LocalStPalette.current
    var case by remember { mutableStateOf<UnreachableCase?>(null) }
    LaunchedEffect(Unit) { case = actions.classify() }

    if (case == UnreachableCase.TailscaleOff) {
        val scope = rememberCoroutineScope()
        val retry by rememberUpdatedState(actions.onRetry)
        DisposableEffect(Unit) {
            var fired = false
            val stop = actions.watchVpn {
                scope.launch {
                    if (!fired) {
                        fired = true
                        retry()
                    }
                }
            }
            onDispose { stop() }
        }
    }

    // The outcome of a Start pressed here, shown for a few seconds.
    var startPressedWith by remember { mutableStateOf<Any?>(NotPressed) }
    var message by remember { mutableStateOf<ActionResult?>(null) }
    LaunchedEffect(lastAction) {
        if (startPressedWith !== NotPressed && lastAction != null && lastAction !== startPressedWith) {
            startPressedWith = NotPressed
            message = lastAction
            delay(MESSAGE_MS)
            message = null
        }
    }

    val (title, text) = when (case) {
        UnreachableCase.TailscaleOff -> "Tailscale is off on this phone" to "Turn it on to reach the tablet."
        UnreachableCase.ServerNotRunning -> "SillyTavern isn't running" to "The tablet is reachable, but SillyTavern isn't serving."
        UnreachableCase.TabletNotAnswering -> "The tablet isn't answering" to "It may be asleep or signed out of Tailscale."
        UnreachableCase.Other, null -> "SillyTavern is unreachable" to state.reason
    }
    Surface(Modifier.fillMaxSize(), color = Color(p.background)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color(p.text), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                text,
                Modifier.padding(vertical = 12.dp),
                color = Color(p.muted),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val button = Modifier.weight(1f)
                when (case) {
                    UnreachableCase.TailscaleOff -> {
                        StButton("Open Tailscale", actions.onOpenTailscale, button)
                        StButton("Retry", actions.onRetry, button)
                    }
                    UnreachableCase.ServerNotRunning -> {
                        StButton(
                            "Start server",
                            {
                                startPressedWith = lastAction
                                actions.onStartServer()
                            },
                            button,
                            enabled = !starting,
                            busy = starting,
                        )
                        StButton("Open Control Center", actions.onOpenControlCenter, button)
                    }
                    else -> {
                        StButton("Retry", actions.onRetry, button)
                        StButton("Open Control Center", actions.onOpenControlCenter, button)
                    }
                }
            }
            message?.let {
                Text(
                    it.message,
                    Modifier.padding(top = 12.dp),
                    color = if (it is ActionResult.Failed) Color(p.dangerBorder) else Color(p.muted),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private object NotPressed
private const val MESSAGE_MS = 6_000L
