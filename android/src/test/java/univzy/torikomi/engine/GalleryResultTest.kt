package univzy.torikomi.engine

import org.junit.Assert.*
import org.junit.Test

class GalleryResultTest {
    @Test fun keepsOnlyActualPhotosAndPreservesOrder() {
        val photos = parseGalleryPhotos("""[[2,{"category":"tiktok"}],[3,"https://cdn.example/a?token=1",{"extension":"jpg","title":"Album","category":"tiktok"}],[3,"https://cdn.example/video.mp4",{"extension":"mp4"}],[3,"https://cdn.example/b.png",{"extension":"png"}],[3,"https://cdn.example/a?token=1",{"extension":"jpg"}]]""")
        assertEquals(2, photos.size)
        assertEquals("Album", photos.first().title)
        assertEquals("https://cdn.example/a?token=1", photos.first().url)
        assertEquals("png", photos.last().extension)
    }
    @Test fun rejectsExtractionErrorsAndUnsafeUrls() {
        assertThrows(IllegalArgumentException::class.java) { parseGalleryPhotos("""[[-1,{"message":"Login required"}]]""") }
        assertThrows(IllegalArgumentException::class.java) { parseGalleryPhotos("[]") }
        assertThrows(IllegalArgumentException::class.java) { parseGalleryPhotos("""[[3,"file:///private/photo.jpg",{"extension":"jpg"}]]""") }
        val rows = (1..101).joinToString(",", "[", "]") { """[3,"https://example.com/$it.jpg",{"extension":"jpg"}]""" }
        assertThrows(IllegalArgumentException::class.java) { parseGalleryPhotos(rows) }
    }
}
