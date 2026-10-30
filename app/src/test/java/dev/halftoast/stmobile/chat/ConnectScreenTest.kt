package dev.halftoast.stmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectScreenTest {
    private val message = "Enter an address starting with http:// or https://"

    @Test fun emptyAddressAsksForOne() = assertEquals(message, connectError(""))

    @Test fun addressWithoutSchemeAsksForOne() = assertEquals(message, connectError("192.168.1.20:8000"))

    @Test fun httpAddressIsAccepted() = assertNull(connectError("http://192.168.1.20:8000"))

    @Test fun surroundingSpacesAreTrimmed() {
        assertNull(connectError("  http://192.168.1.20:8000 "))
        assertEquals(message, connectError("   "))
    }
}
