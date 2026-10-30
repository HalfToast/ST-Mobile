package dev.halftoast.stmobile.stats

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** SillyTavern's avatar thumbnail endpoint for a character's `.png` file name. */
fun avatarUrl(serverUrl: String, file: String): String =
    serverUrl.trimEnd('/') + "/thumbnail?type=avatar&file=" + URLEncoder.encode(file, "UTF-8").replace("+", "%20")

/** Only a plain 200 with an image body counts: a login redirect or HTML error page must not be decoded. */
fun isImageResponse(code: Int, contentType: String?): Boolean =
    code == 200 && contentType?.trim()?.startsWith("image/", ignoreCase = true) == true

/**
 * Least-recently-used cache of at most [maxEntries] fetched values. A null from [fetch] is a failure and is
 * not remembered; concurrent requests for the same file share one fetch.
 */
class AvatarCache<T : Any>(private val maxEntries: Int, private val fetch: suspend (String) -> T?) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private val inFlight = HashMap<String, CompletableDeferred<T?>>()

    suspend fun get(file: String): T? {
        while (true) {
            val mine = CompletableDeferred<T?>()
            val other = mutex.withLock {
                entries[file]?.let { return it }
                inFlight[file] ?: run { inFlight[file] = mine; null }
            }
            if (other == null) return fetchAndPublish(file, mine)
            try {
                return other.await()
            } catch (e: FetchAbandoned) {
                // The caller doing the fetch was cancelled; nothing failed, so try again ourselves.
            }
        }
    }

    private suspend fun fetchAndPublish(file: String, mine: CompletableDeferred<T?>): T? {
        var result: T? = null
        var finished = false
        try {
            result = fetch(file)
            finished = true
            return result
        } finally {
            // Must run even when cancelled, or the key stays in flight forever.
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlight.remove(file)
                    if (result != null) {
                        entries[file] = result
                        while (entries.size > maxEntries) entries.remove(entries.keys.first())
                    }
                }
                if (finished) mine.complete(result) else mine.completeExceptionally(FetchAbandoned())
            }
        }
    }
}

private class FetchAbandoned : RuntimeException()

/**
 * The power-of-two `inSampleSize` that shrinks a [width]×[height] image as far as possible while its
 * shorter side stays at least [targetPx]. 1 for anything unknown.
 */
fun avatarSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** Largest avatar body read; anything bigger is not an avatar worth showing. */
private const val MAX_AVATAR_BYTES = 16 * 1024 * 1024

/**
 * Fetches an avatar with the WebView's ST session cookie and decodes it downsampled to about
 * [targetPx] (ST serves full-size images when its thumbnails are turned off). Any failure, including
 * running out of memory, is null; nothing is logged (private data).
 */
fun httpAvatarFetch(targetPx: Int, serverUrl: () -> String): suspend (String) -> ImageBitmap? = { file ->
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val base = serverUrl()
            if (base.isBlank()) return@withContext null // no address saved
            conn = (URL(avatarUrl(base, file)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                CookieManager.getInstance().getCookie(base)?.let { setRequestProperty("Cookie", it) }
            }
            if (!isImageResponse(conn.responseCode, conn.contentType)) null
            else if (conn.contentLengthLong > MAX_AVATAR_BYTES) null
            else conn.inputStream.use { readAtMost(it, MAX_AVATAR_BYTES) }?.let { decodeAvatar(it, targetPx) }
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** All of [input] if it is at most [limit] bytes; null (after reading just past the limit) if longer. */
fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size() + 1))
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
        if (out.size() > limit) return null
    }
}

private fun decodeAvatar(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
    val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Power-of-two sampling can leave the shorter side near 2× the target, about 4× the memory, in a
    // cache of up to 200 avatars: scale the rest of the way.
    val short = minOf(sampled.width, sampled.height)
    if (short <= targetPx * 5 / 4) return sampled.asImageBitmap()
    val scale = targetPx.toFloat() / short
    val scaled = Bitmap.createScaledBitmap(sampled, (sampled.width * scale).roundToInt(), (sampled.height * scale).roundToInt(), true)
    if (scaled !== sampled) sampled.recycle()
    return scaled.asImageBitmap()
}
