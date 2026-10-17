package univzy.torikomi.engine

import android.media.MediaMetadataRetriever
import univzy.torikomi.data.model.MediaMetadata
import java.io.File

fun readMediaTags(file: File): MediaMetadata? {
    val reader = MediaMetadataRetriever()
    return try {
        reader.setDataSource(file.absolutePath)
        fun text(key: Int) = reader.extractMetadata(key)?.filterNot(Char::isISOControl)?.take(1000)?.takeIf(String::isNotBlank)
        MediaMetadata(
            artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            trackNumber = text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toLongOrNull()?.takeIf { it in 1..4_294_967_295L },
            genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE),
            year = text(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toLongOrNull()?.takeIf { it in 1..9999 },
            duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.let { it / 1000 },
        )
    } catch (_: Exception) { null } finally { reader.release() }
}
