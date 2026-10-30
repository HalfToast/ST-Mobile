package dev.halftoast.stmobile.chat

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class BlobReceiverTest {
    private class FakeFile(val name: String, val mime: String) : DownloadSink.Output {
        val data = ByteArrayOutputStream()
        var committed = false
        var aborted = false
        var failWrites = false
        var failWith: Throwable = java.io.IOException("disk full")
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failWrites) throw failWith
            data.write(bytes, offset, length)
        }
        override fun commit() { committed = true }
        override fun abort() { aborted = true }
    }

    private val files = mutableListOf<FakeFile>()
    private val reports = mutableListOf<String>()
    private var clock = 0L
    private val sink = object : DownloadSink {
        override fun open(name: String, mime: String) = FakeFile(name, mime).also { files += it }
    }
    private val rx = BlobReceiver(sink, { clock }, { reports += it })

    private val nonce = "00112233445566778899aabbccddeeff"
    private val nonceBytes = ByteArray(16) { (it * 0x11).toByte() }

    private fun msg(t: String, vararg kv: Pair<String, Any>) =
        JSONObject().apply { put("n", nonce); put("t", t); kv.forEach { put(it.first, it.second) } }.toString()

    private fun bin(data: ByteArray) = nonceBytes + data

    @Test fun binaryChunksAreAppendedAndCommitted() {
        rx.expect(nonce, "fallback.bin")
        assertEquals("ack:$nonce", rx.onText(msg("start", "size" to 5, "mime" to "application/zip", "name" to "backup.zip")))
        assertEquals("ack:$nonce", rx.onBinary(bin(byteArrayOf(1, 2, 3))))
        assertEquals("ack:$nonce", rx.onBinary(bin(byteArrayOf(4, 5))))
        assertNull(rx.onText(msg("done", "size" to 5)))
        val f = files.single()
        assertEquals("backup.zip", f.name)
        assertEquals("application/zip", f.mime)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), f.data.toByteArray())
        assertTrue(f.committed)
        assertEquals(listOf("Saved backup.zip to Downloads"), reports)
        assertTrue(rx.isIdle)
    }

    @Test fun base64ChunksWork() {
        rx.expect(nonce, "chat.jsonl")
        rx.onText(msg("start", "size" to 3, "name" to ""))
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9))
        assertEquals("ack:$nonce", rx.onText(msg("chunk", "b64" to b64)))
        rx.onText(msg("done", "size" to 3))
        assertEquals("chat.jsonl", files.single().name) // no anchor name: the fallback
        assertArrayEquals(byteArrayOf(7, 8, 9), files.single().data.toByteArray())
        assertTrue(files.single().committed)
    }

    @Test fun nonceIsSingleUse() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 0))
        rx.onText(msg("done", "size" to 0))
        assertNull(rx.onText(msg("start", "size" to 0))) // replay
        assertEquals("abort:$nonce", rx.onBinary(bin(byteArrayOf(1)))) // not taken; the page is told to stop
        assertEquals(1, files.size)
    }

    @Test fun unknownNonceIsIgnoredSilently() {
        assertNull(rx.onText(msg("start", "size" to 1)))
        assertNull(rx.onText(msg("fail", "error" to "x")))
        assertEquals("abort:$nonce", rx.onBinary(bin(byteArrayOf(1)))) // only "stop" back to the page
        assertTrue(files.isEmpty())
        assertTrue(reports.isEmpty()) // a script can't spam toasts with made-up nonces
    }

    @Test fun pageFailureDeletesThePartialFileAndSavesNothing() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 10))
        rx.onBinary(bin(byteArrayOf(1, 2)))
        rx.onText(msg("fail", "error" to "read failed")) // e.g. FileReader gave null
        assertTrue(files.single().aborted)
        assertTrue(!files.single().committed)
        assertEquals(listOf(BlobReceiver.FAILED), reports)
    }

    @Test fun failureBeforeStartReportsWithoutAFile() {
        rx.expect(nonce, "a")
        rx.onText(msg("fail", "error" to "TypeError: Failed to fetch"))
        assertTrue(files.isEmpty())
        assertEquals(listOf(BlobReceiver.FAILED), reports)
    }

    @Test fun shortTransferIsNotSaved() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 4))
        rx.onBinary(bin(byteArrayOf(1, 2)))
        rx.onText(msg("done", "size" to 4))
        assertTrue(files.single().aborted)
        assertTrue(!files.single().committed)
        assertEquals(listOf(BlobReceiver.FAILED), reports)
    }

    @Test fun moreDataThanAnnouncedAborts() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 1))
        assertEquals("abort:$nonce", rx.onBinary(bin(byteArrayOf(1, 2))))
        assertTrue(files.single().aborted)
    }

    @Test fun writeErrorsAndOutOfMemoryAbort() {
        for (t in listOf(java.io.IOException("disk full"), OutOfMemoryError("big"))) {
            files.clear(); reports.clear()
            rx.expect(nonce, "a")
            rx.onText(msg("start", "size" to 3))
            files.single().failWrites = true
            files.single().failWith = t
            assertEquals("abort:$nonce", rx.onBinary(bin(byteArrayOf(1, 2, 3))))
            assertTrue(files.single().aborted)
            assertEquals(listOf(BlobReceiver.FAILED), reports)
            assertTrue(rx.isIdle)
        }
    }

    @Test fun stalledTransferTimesOut() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 3))
        rx.onBinary(bin(byteArrayOf(1)))
        clock += BlobReceiver.IDLE_TIMEOUT_MS - 1
        rx.sweep()
        assertTrue(!files.single().aborted)
        clock += 2
        rx.sweep()
        assertTrue(files.single().aborted)
        assertEquals(listOf(BlobReceiver.FAILED), reports)
        // A late chunk is dropped and tells the page to stop, so it lets go of the blob.
        assertEquals("abort:$nonce", rx.onBinary(bin(byteArrayOf(2))))
        assertEquals("abort:$nonce", rx.onText(msg("chunk", "b64" to "AQ==")))
        assertEquals(listOf(BlobReceiver.FAILED), reports)
        assertTrue(rx.isIdle)
    }

    @Test fun totalTimeIsBounded() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to Long.MAX_VALUE))
        repeat(40) {
            clock += 50_000 // steady, never idle long enough to trip the idle timeout
            rx.onBinary(bin(byteArrayOf(1)))
            rx.sweep()
        }
        assertTrue(files.single().aborted)
    }

    @Test fun unstartedNoncesExpire() {
        rx.expect(nonce, "a")
        clock += BlobReceiver.START_TIMEOUT_MS + 1
        rx.sweep()
        assertTrue(rx.isIdle)
        assertNull(rx.onText(msg("start", "size" to 1)))
    }

    @Test fun abortAllDeletesPartialFiles() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 3))
        rx.onBinary(bin(byteArrayOf(1)))
        rx.abortAll()
        assertTrue(files.single().aborted)
        assertTrue(rx.isIdle)
    }

    @Test fun nameIsSanitised() {
        rx.expect(nonce, "a")
        rx.onText(msg("start", "size" to 0, "name" to "../../etc/evil.json"))
        assertEquals("evil.json", files.single().name)
    }

    @Test fun transferScriptCarriesNonceAndChecksReaderResult() {
        val js = Downloads.transferScript("blob:http://h/u", nonce, "application/json", binary = true)
        assertTrue(js.contains("\"$nonce\""))
        assertTrue(js.contains("bin=true"))
        assertTrue(js.contains("typeof s!=='string'")) // a null FileReader result is a failure, never "null"
        assertTrue(js.contains("b.slice("))
    }
}
