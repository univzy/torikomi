package univzy.torikomi.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import univzy.torikomi.data.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GalleryEngine(private val context: Context) {
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val processes = ConcurrentHashMap<String, Process>()
    private val runtime = File(context.noBackupFilesDir, "gallery-runtime/1.32.16")

    // shortcut: runtime paths match the pinned youtubedl-android 0.17.2 adapter; recheck when upgrading that dependency.
    private fun command(url: String): ProcessBuilder {
        val base = File(context.noBackupFilesDir, YoutubeDL.baseName)
        val packages = File(base, "packages")
        val pythonHome = File(packages, "python/usr")
        val bin = File(context.applicationInfo.nativeLibraryDir)
        check(File(bin, "libpython.so").isFile && pythonHome.isDirectory) { "Android Python runtime is not initialized" }
        val source = publicMediaUrl(url)
        val script = context.assets.open("gallery_entry.py").bufferedReader().use { it.readText() }
        return ProcessBuilder(File(bin, "libpython.so").absolutePath, "-c", script,
            "--config-ignore", "--no-input", "--no-colors", "--resolve-json", "--range", "1-101",
            "--http-timeout", "20", "--retries", "1", "-o", "cache.file=null", "-o", "extractor.netrc=false",
            "-o", "extractor.tiktok.video=false", "-o", "extractor.tiktok.audio=false", "--", source)
            .directory(runtime).apply { environment().apply {
                put("PYTHONHOME", pythonHome.absolutePath)
                put("PYTHONPATH", runtime.absolutePath)
                put("LD_LIBRARY_PATH", listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath })
                put("SSL_CERT_FILE", File(pythonHome, "etc/tls/cert.pem").absolutePath)
                put("REQUESTS_CA_BUNDLE", File(pythonHome, "etc/tls/cert.pem").absolutePath)
                put("HOME", runtime.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("PYTHONDONTWRITEBYTECODE", "1")
                listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy").forEach { remove(it) }
            } }
    }

    @Synchronized private fun install() {
        if (File(runtime, ".ready").isFile) return
        val staging = File(runtime.parentFile, "1.32.16.installing")
        staging.deleteRecursively(); check(staging.mkdirs()) { "Cannot prepare gallery engine" }
        try {
            val packages = JSONArray(context.assets.open("gallery-packages.json").bufferedReader().use { it.readText() })
            for (index in 0 until packages.length()) {
                val entry = packages.getJSONObject(index)
                val data = client.newCall(Request.Builder().url(entry.getString("url")).build()).execute().use { response ->
                    check(response.isSuccessful) { "Gallery setup failed: HTTP ${response.code}" }
                    checkNotNull(response.body).byteStream().use { readBounded(it, 2 * 1024 * 1024) }
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                require(data.size == entry.getInt("bytes") && digest == entry.getString("sha256")) { "Gallery package verification failed" }
                var extracted = 0L
                ZipInputStream(data.inputStream()).use { zip ->
                    while (true) {
                        val file = zip.nextEntry ?: break
                        val target = File(staging, file.name).canonicalFile
                        require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Invalid package path" }
                        if (file.isDirectory) { target.mkdirs(); continue }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extracted += count
                                require(extracted <= 20 * 1024 * 1024) { "Gallery package is too large" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            runtime.deleteRecursively()
            check(staging.renameTo(runtime)) { "Cannot install gallery engine" }
            File(runtime, ".ready").writeText("1.32.16")
        } catch (failure: Throwable) { staging.deleteRecursively(); throw failure }
    }

    fun setup(): String { install(); return version() }
    fun version(): String = if (File(runtime, ".ready").isFile) "1.32.16" else ""

    fun cancel(id: String) { processes.remove(id)?.destroy() }

    fun photos(url: String, id: String): List<GalleryPhoto> {
        install()
        val process = command(url).start()
        processes[id] = process
        val timeout = Timer(true)
        timeout.schedule(object : TimerTask() { override fun run() { process.destroy() } }, 90_000)
        val errors = ByteArrayOutputStream()
        val errorReader = Thread {
            runCatching { process.errorStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (errors.size() < 4096) errors.write(buffer, 0, minOf(count, 4096 - errors.size()))
                }
            } }
        }.apply { start() }
        try {
            val raw = process.inputStream.use { readBounded(it, 2 * 1024 * 1024).toString(Charsets.UTF_8) }
            check(process.waitFor() == 0) { "Gallery extraction failed: ${errors.toString(Charsets.UTF_8.name()).take(300)}" }
            return parseGalleryPhotos(raw)
        } finally { timeout.cancel(); process.destroy(); processes.remove(id); errorReader.join(1000) }
    }

    fun info(url: String): MediaInfo {
        val photos = photos(url, "inspect-${java.util.UUID.randomUUID()}")
        return MediaInfo(url, photos.first().title.ifBlank { "Photo gallery" }, "", photos.first().url, 0, false,
            photos.first().site, listOf(MediaFormat("gallery-images", MediaKind.IMAGE, photos.first().extension, null, null, null, null, "${photos.size} photos")))
    }

    fun download(url: String, output: File, id: String, progress: (Float, Long, String) -> Unit): List<File> {
        val photos = photos(url, id)
        return photos.mapIndexed { index, photo ->
            val directory = File(output, "image-${index + 1}").apply { mkdirs() }
            val downloaded = DirectImages.download(photo.url, directory, { percent, eta, line ->
                progress((index * 100f + percent) / photos.size, eta, line)
            }, photo.extension, url)
            val named = File(directory, "${id.take(40)}-${index + 1}.${photo.extension}")
            check(downloaded.renameTo(named)) { "Cannot name the downloaded photo" }
            named
        }
    }
}

private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Gallery response exceeds its size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
