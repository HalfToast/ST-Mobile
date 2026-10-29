package dev.halftoast.stmobile.chat

object AssetCachePolicy {
    private val prefixes = listOf("/lib/", "/scripts/", "/css/", "/webfonts/", "/img/", "/locales/")
    private val exact = setOf("/lib.js", "/script.js", "/style.css")
    private val excluded = listOf("/scripts/extensions/third-party/")

    fun shouldCache(method: String, path: String): Boolean {
        if (method != "GET") return false
        if (path == "/css/user.css" || excluded.any { path.startsWith(it) }) return false
        return path in exact || prefixes.any { path.startsWith(it) }
    }
}
