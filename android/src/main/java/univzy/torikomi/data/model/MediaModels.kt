package univzy.torikomi.data.model

enum class MediaKind { VIDEO, AUDIO, MUXED, IMAGE, SOURCE }

/** A single yt-dlp format option, mapped from the library's VideoFormat. */
data class MediaFormat(
    val formatId: String,
    val kind: MediaKind,
    val ext: String,
    val resolution: String?,   // e.g. "1920x1080"; null for audio-only
    val heightPx: Int?,        // null/0 means no video height
    val abrKbps: Int?,         // audio bitrate, if known
    val filesizeApprox: Long?, // bytes, if known
    val note: String,          // human-readable label from yt-dlp
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fps: Float? = null,
)

/** Result of fetching info for a URL (maps the library's VideoInfo). */
data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String,
    val thumbnail: String,
    val durationSec: Int,
    val isPlaylist: Boolean,
    val detectedSite: String,            // for display + history.platform
    val formats: List<MediaFormat>,
)

/** A user-selectable quality option; carries the yt-dlp format selector. */
data class QualityChoice(
    val label: String,         // "1080p", "Audio"
    val type: String,          // "video" | "audio"  (history downloadType)
    val formatSelector: String,
    val audioExtract: Boolean, // true -> -x --audio-format mp3
    val sizeBytes: Long? = null, // estimated download size, if known
)

/** Everything DownloadService needs to perform one download. */
data class DownloadSpec(
    val sourceUrl: String,
    val title: String,
    val thumbnail: String,
    val detectedSite: String,
    val choice: QualityChoice,
)

/** A download the user saved for later; persisted in the queue. */
data class QueuedDownload(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val thumbnail: String,
    val detectedSite: String,
    val choiceLabel: String,
    val choiceType: String,
    val choiceSelector: String,
    val choiceAudioExtract: Boolean,
    val addedAt: Long,
    val sizeBytes: Long? = null,
) {
    fun toSpec(): DownloadSpec = DownloadSpec(
        sourceUrl = sourceUrl,
        title = title,
        thumbnail = thumbnail,
        detectedSite = detectedSite,
        choice = QualityChoice(
            label = choiceLabel,
            type = choiceType,
            formatSelector = choiceSelector,
            audioExtract = choiceAudioExtract,
            sizeBytes = sizeBytes,
        ),
    )
}
