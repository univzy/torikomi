package univzy.torikomi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * Platform card shown on the Downloads tab.
 * Maps extension ID or dynamic metadata → brand colour + icon.
 */
@Composable
fun PlatformCard(
    name: String,
    platformName: String? = null,
    version: String,
    extensionId: String,
    appPackageName: String? = null,
    dynamicColor: String? = null,
    dynamicIcon: String? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val (color, icon) = getPlatformMeta(dynamicColor)
    val appIcon = remember(appPackageName) {
        val pkg = appPackageName?.trim().orEmpty()
        if (pkg.isBlank()) null
        else runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    Card(
        onClick    = onClick,
        elevation  = CardDefaults.cardElevation(0.dp),
        modifier   = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon bubble
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = color.copy(alpha = .12f),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon.toBitmap(96, 96).asImageBitmap(),
                            contentDescription = name,
                            modifier = Modifier.size(30.dp),
                        )
                    } else {
                        Icon(icon, contentDescription = name, tint = color, modifier = Modifier.size(30.dp))
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(name,    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                val platformLabel = platformName?.takeIf { it.isNotBlank() }
                if (platformLabel != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Platform: $platformLabel",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(version, style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray))
            }

            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.Gray,
            )
        }
    }
}


@Composable
private fun getPlatformMeta(dColor: String?): Pair<Color, ImageVector> {
    val color = dColor?.let { parseColor(it) } ?: MaterialTheme.colorScheme.primary
    return color to Icons.Rounded.Extension
}

private fun parseColor(hex: String): Color? = try {
    val cleanHex = if (hex.startsWith("#")) hex.substring(1) else hex
    val fullHex = if (cleanHex.length == 6) "FF$cleanHex" else cleanHex
    Color(android.graphics.Color.parseColor("#$fullHex"))
} catch (e: Exception) { null }
