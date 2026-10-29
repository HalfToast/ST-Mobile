package dev.halftoast.stmobile.chat

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class CachedAsset(val mime: String, val encoding: String?, val bytes: ByteArray)

/** Reads `gitRevision` from SillyTavern's `/version` JSON; null if absent or unparseable. */
fun parseGitRevision(versionJson: String): String? = try {
    JSONObject(versionJson).optString("gitRevision", "").takeIf { it.isNotBlank() && it != "null" }
} catch (_: Exception) {
    null
}

/**
 * Disk cache of static assets namespaced by the server's git revision. A new revision wipes it;
 * a null revision disables it. Thread-safe: every operation takes the same lock, so a put can
 * never land in a namespace other than the one it was started for (see [put] with expectedRevision).
 */
class AssetCache(private val root: File) {
    private val lock = Any()

    @Volatile
    var revision: String? = null
        private set

    fun setRevision(revision: String?) = synchronized(lock) {
        if (revision == null) {
            this.revision = null
            return@synchronized
        }
        val stored = File(root, REVISION_FILE).takeIf { it.isFile }?.readText()
        if (stored != revision) {
            wipe()
            root.mkdirs()
            File(root, REVISION_FILE).writeText(revision)
        }
        this.revision = revision
    }

    fun get(url: String): CachedAsset? = synchronized(lock) {
        if (revision == null) return@synchronized null
        val (body, meta) = files(url)
        if (!body.isFile || !meta.isFile) return@synchronized null
        try {
            val lines = meta.readText().split('\n')
            CachedAsset(lines[0], lines.getOrNull(1)?.ifEmpty { null }, body.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    /** Stores [bytes] only if the current revision still equals [expectedRevision] (or it is null = no check). */
    fun put(url: String, mime: String, encoding: String?, bytes: ByteArray, expectedRevision: String? = null) =
        synchronized(lock) {
            val current = revision ?: return@synchronized
            if (expectedRevision != null && expectedRevision != current) return@synchronized
            try {
                val (body, meta) = files(url)
                root.mkdirs()
                meta.delete()
                body.writeBytes(bytes)
                meta.writeText("$mime\n${encoding.orEmpty()}")
            } catch (_: Exception) {
                // disk full etc.: just don't cache
            }
        }

    fun clear() = synchronized(lock) {
        wipe()
        revision = null
    }

    private fun wipe() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun files(url: String): Pair<File, File> {
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "$name.bin") to File(root, "$name.meta")
    }

    private companion object {
        const val REVISION_FILE = "REVISION"
    }
}
