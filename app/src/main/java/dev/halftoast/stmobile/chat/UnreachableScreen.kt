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

/** What the Unreachable screen can do; wired by MainActivity (it outlives the screen's compositions). */
class UnreachableActions(
    /** Works out the [UnreachableCase] (off the main thread). */
    val classify: suspend () -> UnreachableCase,
    val vpnUp: () -> Boolean,
    /** Reports the VPN state (at once, then on changes); returns the unregister function. */
    val watchVpn: (onVpnState: (vpnUp: Boolean) -> Unit) -> () -> Unit,
    /** Kept here, not in the screen: a failed retry re-creates the screen, which must not retry again. */
    val vpnGate: VpnRetryGate = VpnRetryGate(),
    val onRetry: () -> Unit,
    val onOpenControlCenter: () -> Unit,
    val onOpenTailscale: () -> Unit,
    /** The Control Center's Start (or Setup, if SSH isn't set up yet); true if a Start was accepted. */
    val onStartServer: () -> Boolean,
)

/**
 * Chat can't reach SillyTavern. The screen says why, as far as the phone can tell: classified again
 * whenever [state] changes and after every load attempt ([attempt], so also a Retry that fails within
 * a frame). It offers what helps: open Tailscale, start the server, or the Control Center. While the
 * case is being worked out, and when SillyTavern itself answers, it shows the plain [state] reason.
 * With Tailscale off it retries once each time a VPN comes up (see [VpnRetryGate]).
 * [serverOnline]: the dashboard says the process runs, so Start is off, as in the Control Center.
 * [fixedCase]: the case is already known (a connection lost under a loaded chat); no classifying and
 * no VPN auto-retry here, the caller's [UnreachableActions.onRetry] decides. [retrying]: that check is
 * running; Retry shows it is busy.
 */
@Composable
fun UnreachableScreen(
    state: ChatState.Unreachable,
    attempt: Int,
    actions: UnreachableActions,
    starting: Boolean,
    serverOnline: Boolean,
    lastAction: ActionResult?,
    fixedCase: UnreachableCase? = null,
    retrying: Boolean = false,
) {
    val p = LocalStPalette.current
    var classified by remember { mutableStateOf<UnreachableCase?>(null) }
    if (fixedCase == null) {
        LaunchedEffect(state, attempt) {
            classified = null
            classified = actions.classify()
        }
    }
    val case = fixedCase ?: classified

    // A lost connection over a loaded page ([fixedCase]) has its own return path (ConnectionMonitor).
    if (fixedCase == null && case == UnreachableCase.TailscaleOff) {
        val scope = rememberCoroutineScope()
        val retry by rememberUpdatedState(actions.onRetry)
        DisposableEffect(actions) {
            actions.vpnGate.start(actions.vpnUp())
            val stop = actions.watchVpn { up ->
                scope.launch { if (actions.vpnGate.onVpnChange(up)) retry() }
            }
            onDispose { stop() }
        }
    }

    // The outcome of a Start pressed here, shown for a few seconds.
    val startWatch = remember { StartOutcomeWatch() }
    var message by remember { mutableStateOf<ActionResult?>(null) }
    LaunchedEffect(lastAction) { startWatch.onLastAction(lastAction)?.let { message = it } }
    LaunchedEffect(message) {
        if (message != null) {
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
                        StButton("Retry", actions.onRetry, button, enabled = !retrying, busy = retrying)
                    }
                    UnreachableCase.ServerNotRunning -> {
                        StButton(
                            "Start server",
                            {
                                val before = lastAction
                                if (actions.onStartServer()) startWatch.arm(before)
                            },
                            button,
                            enabled = !starting && !serverOnline,
                            busy = starting,
                        )
                        StButton("Open Control Center", actions.onOpenControlCenter, button)
                    }
                    else -> {
                        StButton("Retry", actions.onRetry, button, enabled = !retrying, busy = retrying)
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

private const val MESSAGE_MS = 6_000L
