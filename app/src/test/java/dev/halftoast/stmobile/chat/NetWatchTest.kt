package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetWatchTest {
    @Test fun bridgeAcceptsOnlyFail() {
        assertEquals("STMobileNet", NetWatch.BRIDGE_NAME)
        assertEquals(true, NetWatch.isFailureMessage("fail"))
        listOf(null, "", "Fail", "fail ", "open", "{\"fail\":1}").forEach {
            assertEquals(it, false, NetWatch.isFailureMessage(it))
        }
    }

    @Test fun bundlesItsOwnScript() {
        assertEquals("web/net-watch.js", NetWatch.ASSET_PATH)
        val js = File("src/main/assets/${NetWatch.ASSET_PATH}").readText()
        assertTrue(js.contains("window.__stmNetWatch"))
        assertTrue(js.contains("${NetWatch.BRIDGE_NAME}.postMessage('fail')"))
        assertTrue("never logs", !Regex("""console\.""").containsMatchIn(js))
    }
}
