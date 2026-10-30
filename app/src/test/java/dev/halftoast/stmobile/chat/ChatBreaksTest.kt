package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatBreaksTest {
    @Test fun loadsItsOwnBundledScript() {
        assertEquals("web/chat-breaks.js", ChatBreaks.ASSET_PATH)
        val js = File("src/main/assets/${ChatBreaks.ASSET_PATH}").readText()
        assertTrue(js.contains("window.__stmChatBreaks"))
        assertTrue("separate from the sheet", !js.contains("stm-select-sheet"))
    }

    @Test fun onlyTouchesChatMessages() {
        val js = File("src/main/assets/${ChatBreaks.ASSET_PATH}").readText()
        assertTrue(js.contains("'#chat .mes_text br.' + MARK"))
        assertTrue("never logs page data", !Regex("""console\.""").containsMatchIn(js))
    }
}
