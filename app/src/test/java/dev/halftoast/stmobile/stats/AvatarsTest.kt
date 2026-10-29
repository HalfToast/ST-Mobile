package dev.halftoast.stmobile.stats

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarsTest {
    @Test fun urlEncodesFile() = assertEquals(
        "http://100.101.102.103:8000/thumbnail?type=avatar&file=Mia%20Vale.png",
        avatarUrl("http://100.101.102.103:8000/", "Mia Vale.png"),
    )

    @Test fun failureIsNotCached() = runTest {
        var calls = 0
        val c = AvatarCache<String>(10) { calls++; if (calls == 1) null else "img" }
        assertNull(c.get("a.png")); assertEquals("img", c.get("a.png")); assertEquals("img", c.get("a.png")); assertEquals(2, calls)
    }

    @Test fun evictsOldest() = runTest {
        val seen = mutableListOf<String>()
        val c = AvatarCache<String>(2) { seen += it; it }
        c.get("a"); c.get("b"); c.get("c"); c.get("a"); assertEquals(listOf("a", "b", "c", "a"), seen)
    }

    @Test fun nonImageResponseIsFailure() {
        assertTrue(isImageResponse(200, "image/jpeg")); assertFalse(isImageResponse(200, "text/html; charset=utf-8"))
        assertFalse(isImageResponse(302, "image/png")); assertFalse(isImageResponse(200, null))
    }
}
