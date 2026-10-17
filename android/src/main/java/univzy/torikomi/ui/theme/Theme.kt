package univzy.torikomi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import univzy.torikomi.data.model.AppPreferences
import univzy.torikomi.data.model.AppCatalog

private val Purple80  = Color(0xFFD0BCFF)
private val Purple40  = Color(0xFF6650A4)
private val PurpleContainer80 = Color(0xFFEADDFF)
private val PurpleContainer40 = Color(0xFF4F378B)

private val LightColors = lightColorScheme(
    primary         = Purple40,
    onPrimary       = Color.White,
    primaryContainer = PurpleContainer80,
    onPrimaryContainer = PurpleContainer40,
)

private val DarkColors = darkColorScheme(
    primary         = Purple80,
    onPrimary       = Color(0xFF381E72),
    primaryContainer = PurpleContainer40,
    onPrimaryContainer = PurpleContainer80,
)

@Composable
fun TorikomiDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preferences: AppPreferences = AppPreferences(),
    content: @Composable () -> Unit,
) {
    val preset = AppCatalog.theme(preferences.themeColor)
    val colors = if (darkTheme) preset.dark else preset.light
    val primary = Color(colors.accent)
    val base = if (darkTheme) DarkColors else LightColors
    val colorScheme = base.copy(primary = primary, onPrimary = Color(colors.background),
        background = Color(colors.background), onBackground = Color(colors.foreground),
        surface = Color(colors.background), onSurface = Color(colors.foreground),
        surfaceVariant = Color(colors.panel).copy(alpha = .94f), onSurfaceVariant = Color(colors.muted),
        surfaceContainer = Color(colors.panel).copy(alpha = .94f), surfaceContainerLow = Color(colors.panel).copy(alpha = .94f),
        surfaceContainerHigh = Color(colors.panel).copy(alpha = .96f), surfaceContainerHighest = Color(colors.panel).copy(alpha = .94f),
        surfaceContainerLowest = Color(colors.background), surfaceBright = Color(colors.panel), surfaceDim = Color(colors.background),
        secondary = primary, tertiary = primary, outline = Color(colors.border), outlineVariant = Color(colors.border), surfaceTint = primary,
        primaryContainer = androidx.compose.ui.graphics.lerp(Color(colors.panel), primary, if (darkTheme) .22f else .14f),
        onPrimaryContainer = Color(colors.foreground))
    val family = when (preferences.fontFamily) {
        "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val t = Typography()
    val typography = Typography(
        displayLarge = t.displayLarge.copy(fontFamily = family), displayMedium = t.displayMedium.copy(fontFamily = family), displaySmall = t.displaySmall.copy(fontFamily = family),
        headlineLarge = t.headlineLarge.copy(fontFamily = family), headlineMedium = t.headlineMedium.copy(fontFamily = family), headlineSmall = t.headlineSmall.copy(fontFamily = family),
        titleLarge = t.titleLarge.copy(fontFamily = family), titleMedium = t.titleMedium.copy(fontFamily = family), titleSmall = t.titleSmall.copy(fontFamily = family),
        bodyLarge = t.bodyLarge.copy(fontFamily = family), bodyMedium = t.bodyMedium.copy(fontFamily = family), bodySmall = t.bodySmall.copy(fontFamily = family),
        labelLarge = t.labelLarge.copy(fontFamily = family), labelMedium = t.labelMedium.copy(fontFamily = family), labelSmall = t.labelSmall.copy(fontFamily = family))
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.fontScale)) {
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            Box(Modifier.background(Brush.linearGradient(listOf(Color(colors.gradientStart), Color(colors.gradientEnd))))) {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colorScheme.onBackground, content = content)
            }
        }
    }
}
