package dev.halftoast.stmobile.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: java.io.File
    private lateinit var cache: AssetCache
    private val u = "http://h/scripts/a.js"
    private val m = "text/javascript"
    private val b = "hi".toByteArray()

    @Before fun setUp() { dir = tmp.newFolder("c"); cache = AssetCache(dir) }

    @Test fun nullRevisionBypasses() { cache.setRevision(null); cache.put(u, m, null, b); assertNull(cache.get(u)) }
    @Test fun sameRevisionKeeps() { cache.setRevision("ad29cbda6"); cache.put(u, m, null, b); cache.setRevision("ad29cbda6"); assertArrayEquals(b, cache.get(u)!!.bytes) }
    @Test fun newRevisionWipes() { cache.setRevision("ad29cbda6"); cache.put(u, m, null, b); cache.setRevision("bc81b9f7e"); assertNull(cache.get(u)) }
    @Test fun revisionSurvivesNewInstance() { cache.setRevision("ad29cbda6"); cache.put(u, m, null, b); val again = AssetCache(dir); again.setRevision("ad29cbda6"); assertNotNull(again.get(u)) }
    @Test fun keepsMimeAndEncoding() { cache.setRevision("r"); cache.put(u, m, "utf-8", b); val a = cache.get(u)!!; assertEquals(m, a.mime); assertEquals("utf-8", a.encoding) }
    @Test fun staleRevisionPutIsDropped() { cache.setRevision("old"); cache.setRevision("new"); cache.put(u, m, null, b, expectedRevision = "old"); assertNull(cache.get(u)) }
    @Test fun parsesNamespace() = assertEquals("1.19.0@ad29cbda6", parseCacheNamespace("""{"agent":"x","pkgVersion":"1.19.0","gitRevision":"ad29cbda6","gitBranch":"staging"}"""))
    @Test fun missingRevisionIsNull() = assertNull(parseCacheNamespace("""{"pkgVersion":"1.19.0"}"""))
    @Test fun missingPkgVersionIsNull() = assertNull(parseCacheNamespace("""{"gitRevision":"ad29cbda6"}"""))
    @Test fun garbageIsNull() = assertNull(parseCacheNamespace("not json"))
    @Test fun writesLeaveNoTempFiles() { cache.setRevision("r"); cache.put(u, m, null, b); assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") }) }
}
