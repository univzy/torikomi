package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaKind
import univzy.torikomi.data.model.QualityChoice

/** Each choice is exactly one source format; video-only formats remain silent. */
object QualityPresets {
    fun from(formats: List<MediaFormat>): List<QualityChoice> = formats
        .filter { it.formatId.isNotBlank() && it.ext.lowercase() != "mhtml" }
        .distinctBy { it.formatId }
        .map { format ->
            QualityChoice(
                label = listOfNotNull(
                    format.formatId,
                    format.ext.uppercase().ifBlank { null },
                    format.resolution ?: format.heightPx?.let { "${it}p" },
                    format.fps?.let { "${it.toInt()} fps" },
                    format.videoCodec?.takeUnless { it == "none" },
                    format.audioCodec?.takeUnless { it == "none" },
                    format.abrKbps?.let { "$it kbps" },
                    format.note.takeIf { it.isNotBlank() },
                ).distinct().joinToString(" · "),
                type = when (format.kind) { MediaKind.AUDIO -> "audio"; MediaKind.IMAGE -> "image"; else -> "video" },
                formatSelector = format.formatId,
                audioExtract = false,
                sizeBytes = format.filesizeApprox,
            )
        }
}
