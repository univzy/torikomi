package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaKind
import univzy.torikomi.data.model.QualityChoice

/** Derives a small curated list of quality choices from raw yt-dlp formats. Pure Kotlin. */
object QualityPresets {
    private const val MAX_VIDEO_HEIGHTS = 4

    fun from(formats: List<MediaFormat>): List<QualityChoice> {
        val audioBytes = formats.filter { it.kind == MediaKind.AUDIO }
            .mapNotNull { it.filesizeApprox }.maxOrNull()

        fun videoFormatAt(h: Int): MediaFormat? =
            formats.filter { it.heightPx == h }.maxByOrNull { it.filesizeApprox ?: 0L }
        fun bestVideoFormat(): MediaFormat? =
            formats.filter { (it.heightPx ?: 0) > 0 }.maxByOrNull { it.heightPx ?: 0 }
        fun estimate(video: MediaFormat?): Long? {
            if (video == null) return null
            val v = video.filesizeApprox ?: return null
            return if (video.kind == MediaKind.MUXED) v else v + (audioBytes ?: 0L)
        }

        val out = mutableListOf<QualityChoice>()
        out += QualityChoice(
            label = "Best (video + audio)",
            type = "video",
            formatSelector = "bestvideo*+bestaudio/best",
            audioExtract = false,
            sizeBytes = estimate(bestVideoFormat()) ?: audioBytes,
        )
        val heights = formats.mapNotNull { it.heightPx }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .take(MAX_VIDEO_HEIGHTS)
        for (h in heights) {
            out += QualityChoice(
                label = "${h}p",
                type = "video",
                formatSelector = "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best",
                audioExtract = false,
                sizeBytes = estimate(videoFormatAt(h)),
            )
        }
        out += QualityChoice(
            label = "Audio (MP3)",
            type = "audio",
            formatSelector = "bestaudio/best",
            audioExtract = true,
            sizeBytes = audioBytes,
        )
        return out.distinctBy { it.label }
    }
}
