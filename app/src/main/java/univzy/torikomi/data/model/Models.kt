package univzy.torikomi.data.model

import androidx.compose.ui.graphics.Color


data class DownloadHistory(
    val id: String,
    val platform: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val downloadType: String, // "video" | "audio" | "image"
    val downloadDate: Long,   // epoch millis
    val fileCount: Int = 1,
    val folderUri: String = "default", // SAF tree URI or "default" for Documents
    val fileName: String = "",        // Actual saved filename (e.g. torikomi_youtube_..._ts.mp4)
    val quality: String = "",         // Quality label (e.g. "1080p", "720p", "128kbps")
) {
    val platformIcon: String get() = when (platform.lowercase()) {
        "tiktok"    -> "🎵"
        "youtube"   -> "▶️"
        "instagram" -> "📷"
        "facebook"  -> "👍"
        "twitter", "x" -> "🐦"
        "threads"   -> "🧵"
        "spotify"   -> "🎵"
        "pinterest" -> "📌"
        else        -> "📱"
    }

    val downloadTypeLabel: String get() = when (downloadType) {
        "video" -> "🎬 Video"
        "audio" -> "🎵 Audio"
        else    -> "🖼️ Image"
    }
}


data class DownloadItem(
    val key: String,
    val label: String,
    val type: String,        // "video" | "audio" | "image"
    val url: String,
    val mimeType: String,
    val quality: String,
    val fileSize: Int? = null,
    val extra: Map<String, Any> = emptyMap(),
)

data class ScrapeResult(
    val extensionId: String,
    val platform: String,
    val title: String,
    val author: String = "",
    val authorName: String = "",
    val thumbnail: String = "",
    val duration: Int = 0,
    val downloadItems: List<DownloadItem> = emptyList(),
    val images: List<String> = emptyList(),
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
    val isImagePost: Boolean get() = images.isNotEmpty()
}


enum class ExtensionStatus { AVAILABLE, INSTALLED, UPDATE_AVAILABLE }

data class ExtensionSource(
    val name: String,
    val lang: String,
    val id: String,
    val baseUrl: String,
    val versionId: Int,
)

data class ExtensionInfo(
    val name: String,
    val pkg: String,
    val icon: String = "",
    val apk: String,
    val lang: String,
    val code: Int = 1,
    val version: String,
    val nsfw: Int = 0,
    val sources: List<ExtensionSource> = emptyList(),
    var status: ExtensionStatus = ExtensionStatus.AVAILABLE,
    // Dynamic metadata from extension
    var dynamicPlatform: String? = null,
    var dynamicPlatformName: String? = null,
    var dynamicColor: String? = null,
    var dynamicIcon: String? = null,
    var dynamicDownloaderName: String? = null,
    var dynamicDescription: String? = null,
) {
    /** "com.torikomi.extension_musicaldown" -> "musicaldown" */
    val id: String get() {
        val last = pkg.split(".").last()
        return when {
            last.startsWith("torikomi_extension_") -> last.removePrefix("torikomi_extension_")
            last.startsWith("extension_") -> last.removePrefix("extension_")
            else -> last
        }
    }
    val displayName: String get() = when {
        name.startsWith("Torikomi: ") -> name.removePrefix("Torikomi: ")
        else -> name
    }
    val baseUrl: String get() = sources.firstOrNull()?.baseUrl ?: ""
    val catalogIconUrl: String get() = icon.ifBlank {
        "https://raw.githubusercontent.com/univzy/torikomi-extensions/master/icon/$pkg.png"
    }
}

data class InstalledExtensionInfo(
    val id: String,
    val platform: String,
    val platformName: String? = null,
    val version: String,
    val authority: String,
    val packageName: String,
    val color: String? = null,
    val fontAwesomeIcon: String? = null,
    val downloaderName: String? = null,
    val description: String? = null,
    val urlPlaceholder: String? = null,
)


data class ExtensionMeta(
    val platformName: String,
    val iconRes: Int,           // R.drawable.*
    val color: Color,
    val isSpecialScreen: Boolean = false,
)
