package univzy.torikomi.engine

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PublicMediaUrlsTest {
    @Test fun rejectsPrivateAndCredentialUrls() {
        listOf("https://localhost/x", "https://127.0.0.1/a.jpg", "http://169.254.169.254/x", "https://[::1]/a", "http://[fd00::1]/x", "https://user:pass@example.com/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { publicMediaUrl(it) }
        }
        listOf("10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "0.0.0.0").forEach { assertFalse(isPublicMediaAddress(InetAddress.getByName(it))) }
        assertTrue(isPublicMediaAddress(InetAddress.getByName("8.8.8.8")))
        assertEquals("https://cdn.example/a.jpg", publicMediaUrl("https://cdn.example/a.jpg"))
    }
}
