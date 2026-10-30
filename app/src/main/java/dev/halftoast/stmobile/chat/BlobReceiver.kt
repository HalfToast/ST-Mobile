package dev.halftoast.stmobile.chat

import org.json.JSONObject
import java.util.Base64

/** Where a blob download is written: a pending MediaStore item in the app, a fake in tests. */
interface DownloadSink {
    /** Creates the (still hidden) file. Throws if it can't. */
    fun open(name: String, mime: String): Output

    interface Output {
        fun write(bytes: ByteArray, offset: Int, length: Int)
        /** Closes and publishes the file. Throws if it can't (the caller then calls [abort]). */
        fun commit()
        /** Closes and deletes the file. Never throws. */
        fun abort()
    }
}

/**
 * Receiving end of the chunked blob download (see [Downloads]). The page sends, for one nonce:
 * `start` (size, mime, name), then chunks (ArrayBuffer `nonce bytes + data`, or JSON `chunk` with
 * base64), then `done` (size), each after the previous one was acknowledged; or `fail` at any time.
 *
 * A nonce is accepted once: [expect] registers it, `start` consumes it, and only then are chunks taken.
 * Every write goes straight to the sink, so memory use is one chunk however large the file. Any
 * failure (a write error, out of memory, a size mismatch, a page error, a timeout in [sweep]) deletes
 * the partial file and reports [FAILED]; nothing is ever saved from an incomplete transfer.
 *
 * Not thread-safe: call everything from one thread.
 */
class BlobReceiver(
    private val sink: DownloadSink,
    private val now: () -> Long,
    private val report: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private class Expected(val fallbackName: String, val at: Long)

    private class Transfer(val nonce: String, val name: String, val size: Long, val out: DownloadSink.Output, val startedAt: Long) {
        var received = 0L
        var lastActivity = startedAt
    }

    private val expected = HashMap<String, Expected>()
    private val transfers = HashMap<String, Transfer>()

    val isIdle: Boolean get() = expected.isEmpty() && transfers.isEmpty()

    fun expect(nonce: String, fallbackName: String) {
        expected[nonce] = Expected(fallbackName, now())
    }

    /**
     * Handles a JSON message. Returns the reply for the page (`ack:<nonce>` / `abort:<nonce>`), or null.
     * A chunk for a transfer already swept or failed is answered `abort:` so the page lets go of its blob.
     */
    fun onText(text: String): String? {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val nonce = json.optString("n")
        return when (json.optString("t")) {
            "start" -> start(nonce, json)
            "chunk" -> {
                val t = transfers[nonce] ?: return "abort:$nonce"
                val bytes = try {
                    Base64.getDecoder().decode(json.getString("b64"))
                } catch (e: Throwable) {
                    return fail(t, "bad chunk: $e")
                }
                append(t, bytes, 0, bytes.size)
            }
            "done" -> {
                val t = transfers.remove(nonce) ?: return null
                if (t.received != t.size || json.optLong("size", -1) != t.size) {
                    log("blob download incomplete: ${t.received} of ${t.size} bytes")
                    t.out.abort()
                    report(FAILED)
                    return null
                }
                try {
                    t.out.commit()
                    report("Saved ${t.name} to Downloads")
                } catch (e: Throwable) {
                    log("blob commit failed: $e")
                    t.out.abort()
                    report(FAILED)
                }
                null
            }
            "fail" -> {
                val known = expected.remove(nonce) != null
                val active = transfers.remove(nonce)
                active?.out?.abort()
                if (known || active != null) {
                    log("page reported failure: ${json.optString("error").take(200)}")
                    report(FAILED)
                }
                null
            }
            else -> null
        }
    }

    /** Handles an ArrayBuffer chunk: the 16 nonce bytes, then data. */
    fun onBinary(data: ByteArray): String? {
        if (data.size < NONCE_BYTES) return null
        val nonce = data.copyOfRange(0, NONCE_BYTES).toHex()
        val t = transfers[nonce] ?: return "abort:$nonce"
        return append(t, data, NONCE_BYTES, data.size - NONCE_BYTES)
    }

    /** The WebView is gone: delete every partial file and forget every nonce. */
    fun abortAll() {
        expected.clear()
        transfers.values.toList().forEach { fail(it, "page destroyed") }
    }

    /** Drops nonces never started and transfers that stalled or ran too long. Call periodically. */
    fun sweep() {
        val t0 = now()
        expected.entries.removeAll { t0 - it.value.at > START_TIMEOUT_MS }
        val dead = transfers.values.filter { t0 - it.lastActivity > IDLE_TIMEOUT_MS || t0 - it.startedAt > TOTAL_TIMEOUT_MS }
        for (t in dead) fail(t, "timed out")
    }

    private fun start(nonce: String, json: JSONObject): String? {
        val e = expected.remove(nonce) ?: return null // unknown, expired or already used
        val size = json.optLong("size", -1)
        if (size < 0) {
            report(FAILED)
            return "abort:$nonce"
        }
        val name = sanitize(json.optString("name")).ifEmpty { e.fallbackName }
        val out = try {
            sink.open(name, json.optString("mime"))
        } catch (t: Throwable) {
            log("blob target not created: $t")
            report(FAILED)
            return "abort:$nonce"
        }
        transfers[nonce] = Transfer(nonce, name, size, out, now())
        return "ack:$nonce"
    }

    private fun append(t: Transfer, bytes: ByteArray, offset: Int, length: Int): String? {
        if (t.received + length > t.size) return fail(t, "more data than announced")
        try {
            t.out.write(bytes, offset, length)
        } catch (e: Throwable) {
            return fail(t, "write failed: $e")
        }
        t.received += length
        t.lastActivity = now()
        return "ack:${t.nonce}"
    }

    private fun fail(t: Transfer, why: String): String {
        log("blob download failed: $why")
        transfers.remove(t.nonce)
        t.out.abort()
        report(FAILED)
        return "abort:${t.nonce}"
    }

    companion object {
        const val FAILED = "Download failed"
        const val NONCE_BYTES = 16
        const val START_TIMEOUT_MS = 60_000L
        /** No chunk for this long: the page is gone or stuck. */
        const val IDLE_TIMEOUT_MS = 60_000L
        /** Hard cap on one download, however steady. */
        const val TOTAL_TIMEOUT_MS = 30 * 60_000L

        fun sanitize(name: String) = name.substringAfterLast('/').substringAfterLast('\\').trim()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
