package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPresetsTest {

    private fun videoFmt(height: Int, size: Long? = null) = MediaFormat(
        formatId = "f$height", kind = MediaKind.VIDEO, ext = "mp4",
        resolution = "x$height", heightPx = height, abrKbps = null,
        filesizeApprox = size, note = "${height}p",
    )
    private fun audioFmt(size: Long? = null) = MediaFormat(
        formatId = "a", kind = MediaKind.AUDIO, ext = "m4a",
        resolution = null, heightPx = null, abrKbps = 128,
        filesizeApprox = size, note = "audio",
    )

    @Test fun alwaysStartsWithBestAndEndsWithAudioMp3() {
        val choices = QualityPresets.from(listOf(videoFmt(720), audioFmt()))
        assertEquals("Best (video + audio)", choices.first().label)
        assertEquals("Audio (MP3)", choices.last().label)
        assertTrue(choices.last().audioExtract)
        assertEquals("audio", choices.last().type)
    }

    @Test fun listsDistinctVideoHeightsDescending() {
        val choices = QualityPresets.from(
            listOf(videoFmt(1080), videoFmt(720), videoFmt(720), videoFmt(480), audioFmt())
        )
        val labels = choices.map { it.label }
        assertEquals(listOf("Best (video + audio)", "1080p", "720p", "480p", "Audio (MP3)"), labels)
    }

    @Test fun heightSelectorIsBounded() {
        val choices = QualityPresets.from(listOf(videoFmt(720), audioFmt()))
        val p720 = choices.first { it.label == "720p" }
        assertEquals("bestvideo[height<=720]+bestaudio/best[height<=720]/best", p720.formatSelector)
        assertEquals("video", p720.type)
    }

    @Test fun bestSelectorMergesVideoAndAudio() {
        val choices = QualityPresets.from(listOf(videoFmt(1080), audioFmt()))
        assertEquals("bestvideo*+bestaudio/best", choices.first().formatSelector)
    }

    @Test fun audioOnlySourceStillOffersBestAndAudio() {
        val choices = QualityPresets.from(listOf(audioFmt()))
        assertEquals(listOf("Best (video + audio)", "Audio (MP3)"), choices.map { it.label })
    }

    @Test fun cappedToFourVideoHeights() {
        val choices = QualityPresets.from(
            listOf(videoFmt(2160), videoFmt(1440), videoFmt(1080), videoFmt(720), videoFmt(480), videoFmt(360))
        )
        val videoLabels = choices.filter { it.type == "video" && it.label != "Best (video + audio)" }
        assertEquals(4, videoLabels.size)
        assertEquals(listOf("2160p", "1440p", "1080p", "720p"), videoLabels.map { it.label })
    }

    @Test fun estimatesSizeAsVideoPlusAudio() {
        val choices = QualityPresets.from(
            listOf(videoFmt(1080, 40_000_000L), videoFmt(720, 20_000_000L), audioFmt(4_000_000L))
        )
        assertEquals(44_000_000L, choices.first { it.label == "1080p" }.sizeBytes)
        assertEquals(24_000_000L, choices.first { it.label == "720p" }.sizeBytes)
        assertEquals(4_000_000L, choices.first { it.label == "Audio (MP3)" }.sizeBytes)
        assertEquals(44_000_000L, choices.first().sizeBytes) // Best = top video (1080/40MB) + audio
    }

    @Test fun sizeIsNullWhenFormatsHaveNoSize() {
        val choices = QualityPresets.from(listOf(videoFmt(720), audioFmt()))
        assertEquals(null, choices.first { it.label == "720p" }.sizeBytes)
    }
}
