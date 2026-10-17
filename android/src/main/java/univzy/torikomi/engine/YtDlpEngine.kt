package univzy.torikomi.engine

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaInfo
import univzy.torikomi.data.model.MediaKind
import univzy.torikomi.data.repository.ConvertSettings
import java.io.File

/** Wraps youtubedl-android. One instance held by the Application. */
class YtDlpEngine(private val app: Application) {
    private val gallery = GalleryEngine(app)

    @Volatile private var initialized = false
    private val initMutex = Mutex()

    /** Extracts the bundled Python + FFmpeg payloads. Idempotent. Call once on splash. */
    suspend fun init() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) return@withLock
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
        }
    }

    /** Downloads/updates the yt-dlp binary from GitHub. The required first-launch step. */
    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        init()
        YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        version()
    }

    /** Kills a running download's native yt-dlp process by its id (the DownloadController id). */
    fun cancel(processId: String) {
        gallery.cancel(processId)
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() ?: ""
    }

    fun galleryVersion(): String = gallery.version()
    suspend fun setupGallery(): String = withContext(Dispatchers.IO) { init(); runInterruptible { gallery.setup() } }

    /** Fetches metadata + formats for a URL. */
    suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
        univzy.torikomi.data.model.SyncDocument.mediaUrl(url)
        DirectImages.info(url)?.let { return@withContext it }
        init()
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            CookieStore.cookieFileFor(app, url)?.let { addOption("--cookies", it.absolutePath) }
        }
        if (java.net.URI(url).path.orEmpty().contains("/photo/")) return@withContext runInterruptible { gallery.info(url) }
        val info: VideoInfo = try { YoutubeDL.getInstance().getInfo(request) }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || failure is InterruptedException) throw failure
            return@withContext runInterruptible { gallery.info(url) }
        }
        info.toMediaInfo(url)
    }

    /**
     * Runs a download into [outputDir] (app-private). Returns the produced file.
     * @param outputDir MUST be an exclusive, per-download directory (the produced file is
     *   located by scanning it); sharing it across concurrent downloads is unsafe.
     * @param convert target formats: audio/video are converted by yt-dlp's bundled FFmpeg here;
     *   images are converted afterwards by [ImageConverter] (yt-dlp only downloads them).
     * @param onProgress (percent 0..100, etaSeconds, rawLine)
     */
    suspend fun download(
        spec: DownloadSpec,
        outputDir: File,
        processId: String,
        convert: ConvertSettings,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        if (spec.choice.formatSelector == "direct-image") return@withContext runInterruptible {
            listOf(DirectImages.download(spec.sourceUrl, outputDir, onProgress))
        }
        init()
        if (spec.choice.formatSelector == "gallery-images") return@withContext runInterruptible {
            gallery.download(spec.sourceUrl, outputDir, processId, onProgress)
        }
        val request = YoutubeDLRequest(spec.sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-o", "${outputDir.absolutePath}/%(title).80s.%(ext)s")
            CookieStore.cookieFileFor(app, spec.sourceUrl)?.let { addOption("--cookies", it.absolutePath) }
            addOption("-f", spec.choice.formatSelector)
            if (spec.choice.type == "audio") {
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
            }
            if (spec.choice.audioExtract) {
                addOption("-x")
                addOption("--audio-format", convert.audio)
            } else if (spec.choice.type == "video") {
                addOption("--merge-output-format", if (convert.video in setOf("mp4", "mkv", "webm")) convert.video else "mp4")
                // No-op when the merged file already has the target extension.
                addOption("--recode-video", convert.video)
            }
        }
        try {
            runInterruptible {
                YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                    onProgress(progress, eta, line)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("YtDlpEngine", "download failed for ${spec.sourceUrl}: ${t.message}", t)
            // Cancellation (thread interrupt) or error: ensure the native yt-dlp process is killed.
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw t
        }
        // yt-dlp wrote a single media file into outputDir; pick the largest non-partial file.
        listOf(outputDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                (spec.choice.type == "image" || it.extension.lowercase() !in ImageConverter.IMAGE_EXTS) }
            ?.maxByOrNull { it.length() }
            ?: error("Download produced no file"))
    }

    private fun VideoInfo.toMediaInfo(sourceUrl: String): MediaInfo {
        val mappedFormats = (formats ?: emptyList()).map { f ->
            val h = f.height.takeIf { it > 0 }
            val hasVideo = (f.vcodec != null && f.vcodec != "none") || f.height > 0
            val hasAudio = f.acodec != null && f.acodec != "none"
            MediaFormat(
                formatId = f.formatId ?: "",
                kind = when {
                    f.ext?.lowercase() in ImageConverter.IMAGE_EXTS -> MediaKind.IMAGE
                    hasVideo && hasAudio -> MediaKind.MUXED
                    hasVideo -> MediaKind.VIDEO
                    hasAudio || f.ext?.lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac") -> MediaKind.AUDIO
                    else -> MediaKind.SOURCE
                },
                ext = f.ext ?: "",
                resolution = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null,
                heightPx = h,
                abrKbps = f.abr.takeIf { it > 0 }?.toInt(),
                filesizeApprox = (f.fileSize.takeIf { it > 0 } ?: f.fileSizeApproximate.takeIf { it > 0 }),
                videoCodec = f.vcodec,
                audioCodec = f.acodec,
                fps = f.fps.takeIf { it > 0 }?.toFloat(),
                note = f.formatNote ?: f.format ?: (h?.let { "${it}p" } ?: "audio"),
            )
        }
        val availableFormats = mappedFormats.ifEmpty {
            formatId?.takeIf { it.isNotBlank() }?.let { id ->
                listOf(MediaFormat(id, when {
                    ext?.lowercase() in ImageConverter.IMAGE_EXTS -> MediaKind.IMAGE
                    ext?.lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac") -> MediaKind.AUDIO
                    else -> MediaKind.SOURCE
                }, ext.orEmpty(), resolution, height.takeIf { it > 0 }, null,
                    fileSize.takeIf { it > 0 } ?: fileSizeApproximate.takeIf { it > 0 }, format.orEmpty()))
            }.orEmpty()
        }
        return MediaInfo(
            sourceUrl = sourceUrl,
            title = title ?: "Untitled",
            uploader = uploader ?: "",
            thumbnail = thumbnail ?: "",
            durationSec = duration,
            isPlaylist = false,
            detectedSite = (extractorKey ?: extractor ?: "").ifBlank { "Media" },
            formats = availableFormats,
        )
    }
}
