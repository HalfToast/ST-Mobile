package dev.halftoast.stmobile.ui

import org.json.JSONObject

/** The handful of SillyTavern theme values the native screens follow. Colours are ARGB ints. */
data class StTheme(
    val text: Int,
    val muted: Int,
    val accent: Int,
    val background: Int,
    val border: Int,
    val userTint: Int,
    val botTint: Int,
    val shadow: Int,
    val fontScale: Float,
) {
    fun toJson(): String = JSONObject()
        .put("text", text).put("muted", muted).put("accent", accent).put("background", background)
        .put("border", border).put("userTint", userTint).put("botTint", botTint).put("shadow", shadow)
        .put("fontScale", fontScale.toDouble())
        .toString()

    companion object {
        val Glimmer = StTheme(
            text = 0xFFC3C3C3.toInt(),
            muted = 0xFF96989E.toInt(),
            accent = 0xFFAADBFF.toInt(),
            background = 0xFF18181B.toInt(),
            border = 0x14FFFFFF,
            userTint = 0x12FFFFFF,
            botTint = 0x08FFFFFF,
            shadow = 0xFF000000.toInt(),
            fontScale = 1.1f,
        )

        val CSS_VARS = listOf(
            "--SmartThemeBodyColor", "--SmartThemeEmColor", "--SmartThemeQuoteColor",
            "--SmartThemeBlurTintColor", "--SmartThemeBorderColor", "--SmartThemeUserMesBlurTintColor",
            "--SmartThemeBotMesBlurTintColor", "--SmartThemeShadowColor", "--fontScale",
        )

        private const val MIN_TEXT_ALPHA = 0x4D

        fun fromCssVars(vars: Map<String, String>): StTheme {
            fun color(name: String, fallback: Int) = vars[name]?.let(::parseCssColor) ?: fallback
            val g = Glimmer
            val text = color(CSS_VARS[0], g.text)
            return StTheme(
                text = if ((text ushr 24) < MIN_TEXT_ALPHA) g.text else text,
                muted = color(CSS_VARS[1], g.muted),
                accent = color(CSS_VARS[2], g.accent),
                background = color(CSS_VARS[3], g.background),
                border = color(CSS_VARS[4], g.border),
                userTint = color(CSS_VARS[5], g.userTint),
                botTint = color(CSS_VARS[6], g.botTint),
                shadow = color(CSS_VARS[7], g.shadow),
                fontScale = (vars[CSS_VARS[8]]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: g.fontScale).coerceIn(0.8f, 1.5f),
            )
        }

        fun fromJson(json: String?): StTheme {
            if (json == null) return Glimmer
            return try {
                val o = JSONObject(json)
                val g = Glimmer
                StTheme(
                    text = o.optInt("text", g.text),
                    muted = o.optInt("muted", g.muted),
                    accent = o.optInt("accent", g.accent),
                    background = o.optInt("background", g.background),
                    border = o.optInt("border", g.border),
                    userTint = o.optInt("userTint", g.userTint),
                    botTint = o.optInt("botTint", g.botTint),
                    shadow = o.optInt("shadow", g.shadow),
                    fontScale = o.optDouble("fontScale", g.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.5f),
                )
            } catch (_: Exception) {
                Glimmer
            }
        }
    }
}

/** Opaque colours the native screens draw with, derived from an [StTheme]. */
data class StPalette(
    val background: Int,
    val box: Int,
    val border: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val danger: Int,
    val dangerBorder: Int,
    val online: Int,
)

fun StTheme.palette(): StPalette {
    val bg = composite(background, 0xFF000000.toInt())
    return StPalette(
        background = bg,
        box = composite(0x4D000000, bg),
        border = composite(border, bg),
        text = composite(text, bg),
        muted = composite(muted, bg),
        accent = composite(accent, bg),
        danger = composite(0x738C2828, bg),
        dangerBorder = composite(0x40FF7878, bg),
        online = 0xFF82D296.toInt(),
    )
}
