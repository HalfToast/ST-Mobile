package dev.halftoast.stmobile.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Last chat screen, kept in app-private cacheDir. Contains private chat text: never log or copy it. */
object Snapshot {
    private const val FILE_NAME = "last_screen.jpg"
    private val io = Executors.newSingleThreadExecutor()
    private val ioDispatcher = io.asCoroutineDispatcher()

    private val _cleared = MutableStateFlow(0)
    /** Bumped by [clear]. Key anything holding a loaded bitmap on it, so a cleared snapshot is never shown. */
    val cleared: StateFlow<Int> = _cleared

    private fun file(context: Context) = File(context.cacheDir, FILE_NAME)

    /** Call on the main thread. Draws the view, then compresses and writes off-thread. */
    fun save(context: Context, view: View) {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        // A keyboard-shortened WebView would produce a distorted snapshot.
        if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) return
        val bitmap = try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        } catch (_: Throwable) {
            return
        }
        val target = file(context)
        io.execute {
            try {
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!tmp.renameTo(target)) tmp.delete()
            } catch (_: Exception) {
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Decodes off the main thread, on the save/clear executor so it sees every earlier save or clear. */
    suspend fun load(context: Context): Bitmap? = withContext(ioDispatcher) {
        val f = file(context)
        try {
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        _cleared.update { it + 1 } // drop any bitmap on screen now, before the file is gone
        val target = file(context)
        // Same executor as save, so the delete is ordered after any queued save.
        io.execute {
            target.delete()
            File(target.parentFile, "$FILE_NAME.tmp").delete()
        }
    }
}
