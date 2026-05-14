package univzy.torikomi.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.template.ExtensionScreenTemplate
import univzy.torikomi.ui.template.defaultExtensionConfig

@Composable
fun DownloadScreen(
    platform: String,
    initialUrl: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val placeholder = remember(platform) {
        val bridge = (context.applicationContext as? Torikomi)?.bridge
        val ext = bridge?.runCatching { getInstalledExtensions() }?.getOrNull()
            ?.find { it.id.equals(platform, ignoreCase = true) || it.platform.equals(platform, ignoreCase = true) }
        ext?.urlPlaceholder?.takeIf { it.isNotBlank() } ?: "Paste URL here\u2026"
    }

    ExtensionScreenTemplate(
        platform   = platform,
        config     = defaultExtensionConfig(placeholder = placeholder),
        initialUrl = initialUrl,
        onBack     = onBack,
    )
}

