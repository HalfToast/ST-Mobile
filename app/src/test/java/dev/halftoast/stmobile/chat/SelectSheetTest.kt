package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectSheetTest {
    @Test fun backSeesAnOpenSheet() {
        assertTrue(WebViewHolder.OPEN_UI_SELECTOR.contains("#stm-select-sheet.stm-open"))
        assertTrue("selector is embedded in single-quoted JS", !WebViewHolder.OPEN_UI_SELECTOR.contains("'"))
    }

    @Test fun loadsTheBundledScript() {
        assertEquals("web/select-sheet.js", SelectSheet.ASSET_PATH)
        val asset = File("src/main/assets/${SelectSheet.ASSET_PATH}")
        assertTrue("asset exists at $asset", asset.isFile)
        assertTrue(asset.readText().contains("window.__stmSelectSheet"))
    }

    @Test fun bridgeAcceptsOnlyTheExactWords() {
        assertEquals("STMobileSheet", SelectSheet.BRIDGE_NAME)
        assertEquals(true, SelectSheet.parseBridgeMessage("open"))
        assertEquals(false, SelectSheet.parseBridgeMessage("closed"))
        listOf(null, "", "Open", " open", "open\n", "closed!", "{\"open\":true}", "true").forEach {
            assertNull(it, SelectSheet.parseBridgeMessage(it))
        }
    }

    @Test fun scriptPostsToTheBridge() {
        val js = File("src/main/assets/${SelectSheet.ASSET_PATH}").readText()
        assertTrue(js.contains("${SelectSheet.BRIDGE_NAME}.postMessage"))
    }

    @Test fun aSlidingSheetIsNotCountedAsAnOpenDialog() {
        // The sheet stays dialog[open] while it slides down; only stm-open may count it.
        assertTrue(WebViewHolder.OPEN_UI_SELECTOR.contains("dialog[open]:not(#stm-select-sheet)"))
        assertTrue(!Regex("""dialog\[open\](?!:not\(#stm-select-sheet)""").containsMatchIn(WebViewHolder.OPEN_UI_SELECTOR))
        assertTrue(WebViewHolder.BACK_PROBE_JS.contains(WebViewHolder.OPEN_UI_SELECTOR))
        assertTrue(WebViewHolder.OPEN_UI_COUNT_JS.contains(WebViewHolder.OPEN_UI_SELECTOR))
    }

    @Test fun webTestRunsTheProbesExactJs() {
        val html = File("src/test/web/select-sheet.test.html").readText()
        assertTrue(html.contains("<script type=\"text/plain\" id=\"back-probe\">${WebViewHolder.BACK_PROBE_JS}</script>"))
        assertTrue(html.contains("<script type=\"text/plain\" id=\"open-ui-count\">${WebViewHolder.OPEN_UI_COUNT_JS}</script>"))
    }
}
