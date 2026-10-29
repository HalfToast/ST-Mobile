package dev.halftoast.stmobile.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test fun validIps() = listOf("192.168.1.50", "100.64.0.0/10", "::1", "fd7a:115c:a1e0::/48", "127.0.0.1/32")
        .forEach { assertTrue(it, isValidIpOrCidr(it)) }

    @Test fun invalid() = listOf("", "999.1.1.1", "1.2.3", "1.2.3.4/33", "1.2.3.4\"); require('x", "abc", "1.2.3.4 5.6.7.8", "::1/129", "1.2.3.4/", "1.2.3.4\n")
        .forEach { assertFalse(it, isValidIpOrCidr(it)) }
}
