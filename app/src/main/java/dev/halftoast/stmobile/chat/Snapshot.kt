package dev.halftoast.stmobile.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.util.concurrent.Executors

/** Last chat screen, kept in app-private cacheDir. Contains private chat text: never log or copy it. */
object Snapshot {
    private const val FILE_NAME = "last_screen.jpg"
    private val io = Executors.newSingleThreadExecutor()

    private fun file(context: Context) = File(context.cacheDir, FILE_NAME)

    /** Call on the main thread. Draws the view, then compresses and writes off-thread. */
    fun save(context: Context, view: View) {
        if (view.width <= 0 || view.height <= 0) return
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

    fun load(context: Context): Bitmap? {
        val f = file(context)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
