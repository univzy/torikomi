package univzy.torikomi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDetectorServiceTest {

    @Test fun acceptsKnownPlatformUrl() {
        assertTrue(UrlDetectorService.isValidMediaUrl("https://youtube.com/watch?v=abc"))
    }

    @Test fun acceptsUnknownButValidHttpUrl() {
        assertTrue(UrlDetectorService.isValidMediaUrl("https://some-random-site.example/video/123"))
    }

    @Test fun rejectsNonUrlText() {
        assertFalse(UrlDetectorService.isValidMediaUrl("just some text"))
    }

    @Test fun rejectsNonHttpScheme() {
        assertFalse(UrlDetectorService.isValidMediaUrl("ftp://example.com/file"))
    }

    @Test fun extractsUrlFromSharedText() {
        val shared = "Check this out https://vimeo.com/12345 amazing"
        assertEquals("https://vimeo.com/12345", UrlDetectorService.extractUrl(shared))
    }

    @Test fun extractReturnsNullWhenNoUrl() {
        assertNull(UrlDetectorService.extractUrl("no link here"))
    }

    @Test fun detectPlatformStillWorksForDisplay() {
        assertEquals("YouTube", UrlDetectorService.detectPlatform("https://youtu.be/abc"))
        assertNull(UrlDetectorService.detectPlatform("https://unknown.example/x"))
    }

    @Test fun extractStripsTrailingPunctuation() {
        assertEquals("https://example.com/v/abc", UrlDetectorService.extractUrl("see https://example.com/v/abc."))
    }

    @Test fun folderLookupIsCaseInsensitive() {
        // host validation is case-insensitive
        assertTrue(UrlDetectorService.isValidMediaUrl("HTTPS://Example.COM/x"))
    }
}
