package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStateTest {
    private val all = listOf(
        ChatState.Loading, ChatState.Ready, ChatState.LoginRequired, ChatState.External,
        ChatState.Stalled, ChatState.Unreachable("x"),
    )

    @Test fun serverBackReloadsOnlyDeadOrStalledPages() {
        // Ruling 24 + final review: never Ready, the login form, a page mid-load or an OAuth sign-in.
        assertEquals(
            listOf(ChatState.Stalled, ChatState.Unreachable("x")),
            all.filter { it.reloadWhenServerBack },
        )
    }

    @Test fun backProbesOnlySillyTavernsOwnUi() {
        assertEquals(listOf(ChatState.Ready, ChatState.LoginRequired), all.filter { it.backProbesPage })
    }

    @Test fun onlyLivePagesTellThePanelChatIsBack() {
        assertEquals(listOf(ChatState.Ready, ChatState.LoginRequired), all.filter { it.isLiveServerPage })
    }

    @Test fun theLoadingPillSaysReconnectingOnlyAfterALivePage() {
        // A first load (fresh install, a new address) is connecting, not reconnecting.
        assertEquals("Connecting…", loadingPillText(pageWasLive = false))
        assertEquals("Reconnecting…", loadingPillText(pageWasLive = true))
    }
}
