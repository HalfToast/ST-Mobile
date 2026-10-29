package dev.halftoast.stmobile.nav

import dev.halftoast.stmobile.Overlay
import dev.halftoast.stmobile.Screen

/*
 * Control Center, Stats and Logs slide in from the side over Chat and back out. Chat itself never
 * moves: it stays where it is (WebView and all) under the sliding screen, leaves the composition
 * (and the WebView the window) once that screen covers it, and comes back before it slides away.
 */

/**
 * Chat is what the user sees and nothing covers it, not even a screen sliding out: [covering] is the
 * native screen drawn over Chat, or null. Only then is the onStop snapshot taken.
 */
fun chatSettled(screen: Screen, overlay: Overlay, covering: Screen?): Boolean =
    screen == Screen.Chat && overlay == Overlay.None && covering == null

/** Screens only slide while the keyboard is down and still: nothing animates while it moves. */
fun slideAnimates(imeBottomPx: Int, imeTargetBottomPx: Int): Boolean = imeBottomPx == 0 && imeTargetBottomPx == 0
