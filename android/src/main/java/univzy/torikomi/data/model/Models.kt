package univzy.torikomi.data.model


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
    val fileSize: Long = 0,           // Actual downloaded file size in bytes
    val metadata: MediaMetadata? = null,
    val mediaUri: String = "",
    val libraryId: String = "",
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
