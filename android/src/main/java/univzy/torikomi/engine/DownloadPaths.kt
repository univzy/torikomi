package univzy.torikomi.engine

/** Single source of truth for download destination paths and file naming. Pure Kotlin (no Android). */
object DownloadPaths {
    /** Maps a history downloadType to the media-type subfolder name. */
    val MEDIA_TYPE_FOLDERS: Map<String, String> = mapOf(
        "music" to "MUSIC",
        "audio" to "MUSIC",
        "videos" to "VIDEO",
        "video" to "VIDEO",
        "pictures" to "IMAGES",
        "image" to "IMAGES",
    )

    fun mediaTypeFolder(type: String): String = MEDIA_TYPE_FOLDERS[type.lowercase()] ?: "VIDEO"

    /** Path relative to the public Downloads dir, e.g. "Torikomi/VIDEO". */
    fun relativeDir(type: String): String = "Torikomi/${mediaTypeFolder(type)}"

    fun sanitizeFileName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifBlank { "download" }
}
