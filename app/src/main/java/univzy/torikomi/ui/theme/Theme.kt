package univzy.torikomi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Matches Flutter's Colors.deepPurple seed
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
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content     = content,
    )
}
