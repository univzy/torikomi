package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class QualityPresetsTest {
    @Test fun excludesStoryboardsThatAreNotMediaDownloads() {
        assertTrue(QualityPresets.from(listOf(video("sb0").copy(ext = "mhtml"))).isEmpty())
    }
    private fun video(id: String) = MediaFormat(id, MediaKind.VIDEO, "mp4", "1920x1080", 1080, null, 100, "1080p")
    private fun audio() = MediaFormat("140", MediaKind.AUDIO, "m4a", null, null, 128, 50, "English")

    @Test fun retainsEveryIdEvenAtTheSameResolution() {
        val choices = QualityPresets.from(listOf(video("137"), video("299"), video("137"), audio()))
        assertEquals(listOf("137", "299", "140"), choices.map { it.formatSelector })
        assertTrue(choices[0].label.contains("137"))
        assertTrue(choices[1].label.contains("299"))
    }
    @Test fun rawAudioIsDownloadedWithoutForcedConversion() {
        val choice = QualityPresets.from(listOf(audio())).single()
        assertEquals("140", choice.formatSelector)
        assertEquals("audio", choice.type)
        assertFalse(choice.audioExtract)
        assertEquals(50L, choice.sizeBytes)
    }
    @Test fun silentVideoDoesNotSilentlyChooseAnAudioTrack() {
        val choice = QualityPresets.from(listOf(video("137"), audio())).first()
        assertEquals("137", choice.formatSelector)
        assertEquals(100L, choice.sizeBytes)
    }
    @Test fun noFormatsDoesNotInventBestFallbacks() {
        assertTrue(QualityPresets.from(emptyList()).isEmpty())
    }
    @Test fun labelsRetainCodecFpsAndSourceDetails() {
        val choice = QualityPresets.from(listOf(video("299").copy(videoCodec = "avc1", fps = 60f))).single()
        listOf("299", "MP4", "1920x1080", "avc1", "60 fps").forEach { assertTrue(choice.label.contains(it)) }
    }
    @Test fun imagesKeepTheirExactSourceFormat() {
        val choice = QualityPresets.from(listOf(video("original").copy(kind = MediaKind.IMAGE, ext = "jpg"))).single()
        assertEquals("image", choice.type)
        assertEquals("original", choice.formatSelector)
        assertFalse(choice.audioExtract)
    }
}
