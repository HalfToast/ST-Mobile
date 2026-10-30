package dev.halftoast.stmobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun acceptsHttpUrlWithPort() = assertTrue(AppSettings.isValidServerUrl("http://100.101.102.103:8000"))
    @Test fun acceptsHttps() = assertTrue(AppSettings.isValidServerUrl("https://server.tailnet.ts.net"))
    @Test fun rejectsMissingScheme() = assertFalse(AppSettings.isValidServerUrl("100.101.102.103:8000"))
    @Test fun rejectsBlank() = assertFalse(AppSettings.isValidServerUrl(" "))

    @Test fun sshNeedsBothHostAndUser() {
        assertTrue(AppSettings.isSshConfigured("100.101.102.103", "u0_a123"))
        assertFalse(AppSettings.isSshConfigured("", "u0_a123")) // cleared in Settings
        assertFalse(AppSettings.isSshConfigured("100.101.102.103", " "))
        assertFalse(AppSettings.isSshConfigured("", "")) // never set up
    }

    @Test fun sshScreensNeedSetupAndSsh() {
        assertTrue(sshScreensReady(setupComplete = true, hasSsh = true))
        assertFalse(sshScreensReady(setupComplete = true, hasSsh = false)) // SSH cleared after setup
        assertFalse(sshScreensReady(setupComplete = false, hasSsh = true))
    }

    @Test fun notSetUpSaysWhereToAddSsh() {
        val message = setupErrorMessage(dev.halftoast.stmobile.ssh.SshError.NotSetUp(), keyStage = false)
        assertTrue(message, "SSH isn't set up" in message && "Settings" in message)
        assertFalse(message, "tablet" in message)
    }

    @Test fun defaultSshPortIsTheStandardOne() = assertEquals(22, AppSettings.DEFAULT_SSH_PORT)

    @Test fun legacyUrlForSetUpInstallsWithoutASavedUrl() {
        assertEquals("http://100.101.102.103:8000", legacyServerUrl(false, true, "100.101.102.103"))
        assertEquals("http://[fd7a:115c:a1e0::1]:8000", legacyServerUrl(false, true, "fd7a:115c:a1e0::1"))
    }

    @Test fun noLegacyUrlOtherwise() {
        assertNull(legacyServerUrl(true, true, "100.101.102.103")) // URL saved
        assertNull(legacyServerUrl(false, false, "100.101.102.103")) // never set up
        assertNull(legacyServerUrl(false, true, "")) // no SSH host to take it from
    }
}
