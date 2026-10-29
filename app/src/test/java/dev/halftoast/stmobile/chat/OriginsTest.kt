package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginsTest {
    private val server = Origins.of("http://100.101.102.103:8000/")

    @Test fun originOfServerUrl() = assertEquals("http://100.101.102.103:8000", server)

    @Test fun normalisesCaseAndDefaultPorts() {
        assertEquals("http://example.com", Origins.of("HTTP://Example.COM:80/x?y#z"))
        assertEquals("https://example.com", Origins.of("https://example.com:443"))
        assertEquals("https://example.com:8443", Origins.of("https://user:pw@example.com:8443/"))
        assertEquals("http://[::1]:8000", Origins.of("http://[::1]:8000/"))
    }

    @Test fun nonHttpHasNoOrigin() {
        assertNull(Origins.of("blob:http://100.101.102.103:8000/uuid"))
        assertNull(Origins.of("about:blank"))
        assertNull(Origins.of("mailto:a@b.c"))
        assertNull(Origins.of(null))
        assertNull(Origins.of("http://:80/"))
    }

    @Test fun sameOriginChecks() {
        assertTrue(Origins.isOn("http://100.101.102.103:8000/callback/openrouter?code=1", server))
        assertFalse(Origins.isOn("https://openrouter.ai/auth?callback_url=x", server))
        assertFalse(Origins.isOn("http://100.101.102.103:8001/", server)) // other port
        assertFalse(Origins.isOn("https://100.101.102.103:8000/", server)) // other scheme
        assertFalse(Origins.isOn("http://100.101.102.103:8000/", null))
    }

    @Test fun newWindowsNeverLoadInTheWebView() {
        assertEquals(LinkTarget.Browser, LinkPolicy.forNewWindow("https://docs.sillytavern.app/"))
        assertEquals(LinkTarget.Browser, LinkPolicy.forNewWindow("http://100.101.102.103:8000/user/images/a.png")) // same origin too
        assertEquals(LinkTarget.ExternalApp, LinkPolicy.forNewWindow("mailto:a@b.c"))
        assertEquals(LinkTarget.ExternalApp, LinkPolicy.forNewWindow("tel:+123"))
        assertEquals(LinkTarget.ExternalApp, LinkPolicy.forNewWindow("intent://x#Intent;scheme=y;end"))
        assertEquals(LinkTarget.Ignore, LinkPolicy.forNewWindow("javascript:alert(1)"))
        assertEquals(LinkTarget.Ignore, LinkPolicy.forNewWindow("blob:http://h/u"))
        assertEquals(LinkTarget.Ignore, LinkPolicy.forNewWindow("file:///sdcard/x"))
        assertEquals(LinkTarget.Ignore, LinkPolicy.forNewWindow(""))
    }

    @Test fun sameWindowHttpStaysOtherSchemesLeave() {
        assertNull(LinkPolicy.forMainFrame("https://openrouter.ai/auth")) // Ruling 10: OAuth works
        assertNull(LinkPolicy.forMainFrame("http://100.101.102.103:8000/"))
        assertNull(LinkPolicy.forMainFrame("about:blank"))
        assertEquals(LinkTarget.ExternalApp, LinkPolicy.forMainFrame("mailto:a@b.c"))
        assertEquals(LinkTarget.ExternalApp, LinkPolicy.forMainFrame("TEL:+123"))
        assertEquals(LinkTarget.Ignore, LinkPolicy.forMainFrame("content://x/y"))
    }
}
