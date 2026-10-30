package dev.halftoast.stmobile.ui

import kotlin.math.roundToInt

private val FUNC = Regex("""^rgba?\(\s*([^,\s)]+)\s*,\s*([^,\s)]+)\s*,\s*([^,\s)]+)\s*(?:,\s*([^,\s)]+)\s*)?\)$""", RegexOption.IGNORE_CASE)
private val HEX = Regex("""^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$""", RegexOption.IGNORE_CASE)

private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

/** Parses the colour notations SillyTavern writes into its CSS variables to an ARGB int; null when unreadable. */
fun parseCssColor(css: String): Int? {
    val s = css.trim()
    HEX.matchEntire(s)?.let { m ->
        val h = m.groupValues[1]
        return when (h.length) {
            3 -> argb(0xFF, h.substring(0, 1).repeat(2).toInt(16), h.substring(1, 2).repeat(2).toInt(16), h.substring(2, 3).repeat(2).toInt(16))
            6 -> 0xFF000000.toInt() or h.toInt(16)
            else -> {
                val v = h.toLong(16)
                (((v and 0xFF) shl 24) or (v ushr 8)).toInt()
            }
        }
    }
    val m = FUNC.matchEntire(s) ?: return null
    // toDoubleOrNull takes "NaN" and "Infinity", and NaN.roundToInt() throws.
    fun num(v: String) = v.toDoubleOrNull()?.takeIf { it.isFinite() }
    val ch = (1..3).map { num(m.groupValues[it])?.roundToInt()?.coerceIn(0, 255) ?: return null }
    val a = if (m.groupValues[4].isEmpty()) 1.0 else num(m.groupValues[4]) ?: return null
    return argb((a.coerceIn(0.0, 1.0) * 255).roundToInt(), ch[0], ch[1], ch[2])
}

/** Source-over: [top] painted on [bottom]. Opaque when [bottom] is opaque. */
fun composite(top: Int, bottom: Int): Int {
    val ta = (top ushr 24) / 255.0
    val ba = (bottom ushr 24) / 255.0
    val oa = ta + ba * (1 - ta)
    if (oa <= 0.0) return 0
    fun c(shift: Int): Int {
        val t = (top ushr shift) and 0xFF
        val b = (bottom ushr shift) and 0xFF
        return ((t * ta + b * ba * (1 - ta)) / oa).roundToInt().coerceIn(0, 255)
    }
    return argb((oa * 255).roundToInt(), c(16), c(8), c(0))
}
