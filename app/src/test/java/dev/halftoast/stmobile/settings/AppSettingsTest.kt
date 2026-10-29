package dev.halftoast.stmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun acceptsHttpUrlWithPort() = assertTrue(AppSettings.isValidServerUrl("http://100.101.102.103:8000"))
    @Test fun acceptsHttps() = assertTrue(AppSettings.isValidServerUrl("https://tablet.tailnet.ts.net"))
    @Test fun rejectsMissingScheme() = assertFalse(AppSettings.isValidServerUrl("100.101.102.103:8000"))
    @Test fun rejectsBlank() = assertFalse(AppSettings.isValidServerUrl(" "))
    @Test fun defaultsMatchSpec() {
        assertEquals("http://100.101.102.103:8000", AppSettings.DEFAULT_SERVER_URL)
        assertEquals("100.101.102.103", AppSettings.DEFAULT_SSH_HOST)
    }
}
