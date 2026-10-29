package dev.halftoast.stmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.halftoast.stmobile.R

@OptIn(ExperimentalTextApi::class)
private fun variableFamily(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

private val NotoSans = variableFamily(R.font.noto_sans, 400, 500, 600)

val StMono: FontFamily = variableFamily(R.font.noto_sans_mono, 400)

val LocalStPalette: ProvidableCompositionLocal<StPalette> = staticCompositionLocalOf { StTheme.Glimmer.palette() }

private fun TextStyle.scaled(scale: Float) = copy(
    fontFamily = NotoSans,
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
)

private fun typography(scale: Float): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.scaled(scale), displayMedium = d.displayMedium.scaled(scale),
        displaySmall = d.displaySmall.scaled(scale), headlineLarge = d.headlineLarge.scaled(scale),
        headlineMedium = d.headlineMedium.scaled(scale), headlineSmall = d.headlineSmall.scaled(scale),
        titleLarge = d.titleLarge.scaled(scale), titleMedium = d.titleMedium.scaled(scale),
        titleSmall = d.titleSmall.scaled(scale), bodyLarge = d.bodyLarge.scaled(scale),
        bodyMedium = d.bodyMedium.scaled(scale), bodySmall = d.bodySmall.scaled(scale),
        labelLarge = d.labelLarge.scaled(scale), labelMedium = d.labelMedium.scaled(scale),
        labelSmall = d.labelSmall.scaled(scale),
    )
}

@Composable
fun StMobileTheme(theme: StTheme, content: @Composable () -> Unit) {
    val p = remember(theme) { theme.palette() }
    val scheme = remember(p) {
        darkColorScheme(
            primary = Color(p.accent), onPrimary = Color(p.background),
            background = Color(p.background), onBackground = Color(p.text),
            surface = Color(p.background), onSurface = Color(p.text), onSurfaceVariant = Color(p.muted),
            outline = Color(p.border), outlineVariant = Color(p.border),
            surfaceContainer = Color(p.box), surfaceContainerHigh = Color(p.box), surfaceContainerHighest = Color(p.box),
            secondaryContainer = Color(p.box), onSecondaryContainer = Color(p.text),
            error = Color(0xFFFF8C8C), errorContainer = Color(p.danger), onErrorContainer = Color(p.text),
        )
    }
    val type = remember(theme.fontScale) { typography(theme.fontScale) }
    CompositionLocalProvider(LocalStPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
