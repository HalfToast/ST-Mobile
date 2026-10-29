package dev.halftoast.stmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StThemeTest {
    @Test fun parsesStFormats() {
        assertEquals(0xFFC3C3C3.toInt(), parseCssColor("rgba(195, 195, 195, 1)"))
        assertEquals(0x14FFFFFF, parseCssColor("rgba(255,255,255,0.08)"))   // 0.08*255 = 20.4 → 20
        assertEquals(0xFF18181B.toInt(), parseCssColor("rgb(24, 24, 27)"))
        assertEquals(0xFFAADBFF.toInt(), parseCssColor("#aadbff"))
        assertEquals(0x80AADBFF.toInt(), parseCssColor("#aadbff80"))
        assertNull(parseCssColor("transparent")); assertNull(parseCssColor("")); assertNull(parseCssColor("var(--x)"))
    }
    @Test fun parsesShortHex() {
        assertEquals(0xFFAABBCC.toInt(), parseCssColor("#abc"))
    }
    @Test fun glimmerMatchesSpec() {
        val g = StTheme.Glimmer
        assertEquals(0xFFC3C3C3.toInt(), g.text); assertEquals(0xFF96989E.toInt(), g.muted)
        assertEquals(0xFFAADBFF.toInt(), g.accent); assertEquals(0xFF18181B.toInt(), g.background)
        assertEquals(0x14FFFFFF, g.border); assertEquals(1.1f, g.fontScale)
    }
    @Test fun readsStVars() {
        val t = StTheme.fromCssVars(mapOf("--SmartThemeQuoteColor" to "rgba(255, 0, 0, 1)", "--fontScale" to "1.25"))
        assertEquals(0xFFFF0000.toInt(), t.accent); assertEquals(1.25f, t.fontScale)
        assertEquals(StTheme.Glimmer.text, t.text) // missing → Glimmer
    }
    @Test fun unreadableValuesFallBack() {
        val t = StTheme.fromCssVars(mapOf("--SmartThemeBodyColor" to "transparent", "--SmartThemeBlurTintColor" to "", "--fontScale" to "abc"))
        assertEquals(StTheme.Glimmer.text, t.text); assertEquals(StTheme.Glimmer.background, t.background); assertEquals(1.1f, t.fontScale)
    }
    @Test fun nearlyTransparentTextFallsBack() {
        assertEquals(StTheme.Glimmer.text, StTheme.fromCssVars(mapOf("--SmartThemeBodyColor" to "rgba(200,200,200,0.1)")).text)
    }
    @Test fun fontScaleIsClamped() {
        assertEquals(1.5f, StTheme.fromCssVars(mapOf("--fontScale" to "3")).fontScale)
        assertEquals(0.8f, StTheme.fromCssVars(mapOf("--fontScale" to "0.2")).fontScale)
    }
    @Test fun translucentBackgroundBecomesOpaqueOverBlack() {
        assertEquals(0xFF0C0C0E.toInt(), StTheme.fromCssVars(mapOf("--SmartThemeBlurTintColor" to "rgba(24, 24, 27, 0.5)")).palette().background)
    }
    @Test fun jsonRoundTripAndBadJson() {
        val t = StTheme.fromCssVars(mapOf("--SmartThemeQuoteColor" to "#ff0000"))
        assertEquals(t, StTheme.fromJson(t.toJson()))
        assertEquals(StTheme.Glimmer, StTheme.fromJson(null)); assertEquals(StTheme.Glimmer, StTheme.fromJson("{oops"))
    }
    @Test fun paletteIsOpaqueAndMatchesMockup() {
        val p = StTheme.Glimmer.palette()
        assertEquals(0xFF2A2A2D.toInt(), p.border)      // 8% white (alpha 20) over #18181B
        assertEquals(0xFF111113.toInt(), p.box)         // ST --black30a: rgba(0,0,0,0.3) over #18181B
        listOf(p.background, p.box, p.border, p.danger, p.dangerBorder).forEach { assertEquals(0xFF, it ushr 24) }
    }
    @Test fun storeSavesOnlyOnChange() {
        var saved = 0; var stored: String? = null
        val s = ThemeStore(load = { stored }, save = { stored = it; saved++ })
        assertEquals(StTheme.Glimmer, s.theme.value)
        s.update(mapOf("--SmartThemeQuoteColor" to "#ff0000")); s.update(mapOf("--SmartThemeQuoteColor" to "#ff0000"))
        assertEquals(1, saved); assertEquals(0xFFFF0000.toInt(), ThemeStore({ stored }, {}).theme.value.accent)
    }
}
