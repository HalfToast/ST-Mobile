package dev.halftoast.stmobile.chat

sealed interface ChatState {
    /** The server's page is loading; the snapshot overlay shows (and swallows taps) until it's ready. */
    data object Loading : ChatState
    data object Ready : ChatState
    /** SillyTavern's login page is showing; the user must sign in. Not an error. */
    data object LoginRequired : ChatState
    /**
     * The main frame is on another site (e.g. the OpenRouter sign-in, whose callback must load here).
     * Shown as it is: no overlay, no readiness polling. Returning to the server origin goes back to
     * [Loading].
     */
    data object External : ChatState
    /**
     * A page from the server loaded but SillyTavern didn't become ready in time. The page is shown as
     * it is, with a small "Still loading… Retry" bar; it still turns [Ready] if SillyTavern shows up.
     */
    data object Stalled : ChatState
    data class Unreachable(val reason: String) : ChatState
}

/**
 * The Control Center saw the server serving again: reload only a Chat with nothing live to lose.
 * Never a working page (Ready, the login form, a page being loaded, or a sign-in on another site).
 */
val ChatState.reloadWhenServerBack: Boolean
    get() = this is ChatState.Unreachable || this == ChatState.Stalled

/**
 * Back may probe the page (close ST's own popups) only when SillyTavern's UI is what the user sees.
 * Elsewhere the page is hidden, foreign or not ST: plain back (history, then "press again").
 */
val ChatState.backProbesPage: Boolean
    get() = this == ChatState.Ready || this == ChatState.LoginRequired

/** A live page against a running server, so the Control Center has nothing left to recover. */
val ChatState.isLiveServerPage: Boolean
    get() = this == ChatState.Ready || this == ChatState.LoginRequired

/**
 * A loaded server page, so a connection lost under it can be covered (and uncovered without a
 * reload): Ready, the login form, or a page shown although ST was slow to start (Stalled).
 */
val ChatState.coversOnConnectionLoss: Boolean
    get() = this == ChatState.Ready || this == ChatState.LoginRequired || this == ChatState.Stalled

/** The Loading pill: a first load connects; once a page from this server was live, it reconnects. */
fun loadingPillText(pageWasLive: Boolean): String = if (pageWasLive) "Reconnecting…" else "Connecting…"
