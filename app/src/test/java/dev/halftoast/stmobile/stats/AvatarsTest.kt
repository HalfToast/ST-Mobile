package dev.halftoast.stmobile.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    @Test fun waiterSurvivesCancelledFirstCaller() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val c = AvatarCache<String>(10) { calls++; if (calls == 1) { gate.await(); "first" } else "img" }
        val a = launch { c.get("a.png") }
        runCurrent()
        val b = async { c.get("a.png") }
        runCurrent()
        a.cancel(); runCurrent()
        assertEquals("img", b.await())
    }

    @Test fun cancelledFetchDoesNotWedgeKey() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val c = AvatarCache<String>(10) { calls++; if (calls == 1) { gate.await(); "first" } else "img" }
        val a = launch { c.get("a.png") }
        runCurrent()
        a.cancel(); runCurrent()
        assertEquals("img", c.get("a.png")); assertEquals(2, calls)
    }

    @Test fun concurrentGetsShareOneFetch() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val c = AvatarCache<String>(10) { calls++; gate.await(); "img" }
        val x = async { c.get("a.png") }
        val y = async { c.get("a.png") }
        runCurrent(); gate.complete(Unit)
        assertEquals("img", x.await()); assertEquals("img", y.await()); assertEquals(1, calls)
    }
}
