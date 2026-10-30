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
        "http://192.168.1.20:8000/thumbnail?type=avatar&file=Mia%20Vale.png",
        avatarUrl("http://192.168.1.20:8000/", "Mia Vale.png"),
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

    @Test fun sampleSizeShrinksBigImagesToAboutTheTarget() {
        assertEquals(1, avatarSampleSize(96, 144, 220))     // ST's own thumbnail: as is
        assertEquals(1, avatarSampleSize(400, 600, 220))    // halving would drop below the target
        assertEquals(2, avatarSampleSize(512, 768, 220))
        assertEquals(4, avatarSampleSize(1000, 1500, 220))
        assertEquals(16, avatarSampleSize(4096, 4096, 220))
        assertEquals(1, avatarSampleSize(0, 0, 220)); assertEquals(1, avatarSampleSize(-1, 500, 220))
        assertEquals(1, avatarSampleSize(1000, 1000, 0))
    }

    @Test fun readsAtMostTheLimit() {
        val ten = ByteArray(10) { it.toByte() }
        assertEquals(10, readAtMost(ten.inputStream(), 10)?.size)
        assertEquals(ten.toList(), readAtMost(ten.inputStream(), 64)?.toList())
        assertNull(readAtMost(ten.inputStream(), 9))
        // Never reads far past the limit: a huge body stops after limit + one buffer.
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertNull(readAtMost(endless, 100_000))
        assertTrue("read $served bytes", served <= 100_000 + 16 * 1024)
    }
}
