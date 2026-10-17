package univzy.torikomi.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import univzy.torikomi.data.model.*
import java.io.File
import java.net.URI

object DirectImages {
    private val client = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
            require(addresses.isNotEmpty() && addresses.all(::isPublicMediaAddress)) { "Private network URLs are not supported" }
        }
    }).addNetworkInterceptor { chain ->
        publicMediaUrl(chain.request().url.toString())
        chain.proceed(chain.request())
    }.build()
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

    fun extension(url: String): String? = runCatching {
        publicMediaUrl(url)
        URI(url).path.substringAfterLast('.', "").lowercase().takeIf { it in ImageConverter.IMAGE_EXTS }
    }.getOrNull()

    fun info(url: String): MediaInfo? = extension(url)?.let { ext ->
        MediaInfo(url, URI(url).path.substringAfterLast('/').ifBlank { "Photo" }, "", url, 0, false,
            URI(url).host, listOf(MediaFormat("direct-image", MediaKind.IMAGE, ext, null, null, null, null, "Photo")))
    }

    fun download(url: String, destination: File, onProgress: (Float, Long, String) -> Unit, imageExtension: String? = null, referer: String? = null): File {
        val ext = requireNotNull(imageExtension ?: extension(url)) { "Unsupported image URL" }
        require(ext in ImageConverter.IMAGE_EXTS) { "Unsupported image format" }
        val target = File(destination, "photo.$ext")
        try {
            val request = Request.Builder().url(publicMediaUrl(url)).header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            referer?.let { request.header("Referer", SyncDocument.mediaUrl(it)) }
            client.newCall(request.build()).execute().use { response ->
                check(response.isSuccessful) { "Image download failed: HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "Empty image response" }
                require(body.contentType()?.type == "image") { "The URL did not return an image" }
                require(body.contentLength() <= MAX_IMAGE_BYTES) { "Photo exceeds 50 MiB" }
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Download cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES) { "Photo exceeds 50 MiB" }
                        output.write(buffer, 0, count)
                        onProgress(if (body.contentLength() > 0) total * 100f / body.contentLength() else 0f, 0, "")
                    }
                    check(total > 0) { "Empty image response" }
                } }
            }
            return target
        } catch (error: Throwable) { target.delete(); throw error }
    }
}
