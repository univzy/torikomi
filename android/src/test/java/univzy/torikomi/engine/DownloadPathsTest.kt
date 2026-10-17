package univzy.torikomi.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPathsTest {
    @Test fun videoTypeMapsToVideoFolder() {
        assertEquals("VIDEO", DownloadPaths.mediaTypeFolder("video"))
    }

    @Test fun audioTypeMapsToMusicFolder() {
        assertEquals("MUSIC", DownloadPaths.mediaTypeFolder("audio"))
    }

    @Test fun imageTypeMapsToImagesFolder() {
        assertEquals("IMAGES", DownloadPaths.mediaTypeFolder("image"))
    }

    @Test fun unknownTypeFallsBackToVideo() {
        assertEquals("VIDEO", DownloadPaths.mediaTypeFolder("whatever"))
    }

    @Test fun relativeDirIsTorikomiPlusTypeFolder() {
        assertEquals("Torikomi/MUSIC", DownloadPaths.relativeDir("audio"))
    }

    @Test fun sanitizeRemovesIllegalFileNameChars() {
        assertEquals("a_b_c", DownloadPaths.sanitizeFileName("a/b:c"))
    }

    @Test fun sanitizeBlankFallsBackToDownload() {
        assertEquals("download", DownloadPaths.sanitizeFileName("   "))
    }
}
