package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
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
}
