package dev.halftoast.stmobile.stats

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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

/** Fetches an avatar with the WebView's ST session cookie. Any failure is null; nothing is logged (private data). */
fun httpAvatarFetch(serverUrl: () -> String): suspend (String) -> ImageBitmap? = { file ->
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val base = serverUrl()
            conn = (URL(avatarUrl(base, file)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                CookieManager.getInstance().getCookie(base)?.let { setRequestProperty("Cookie", it) }
            }
            if (!isImageResponse(conn.responseCode, conn.contentType)) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
