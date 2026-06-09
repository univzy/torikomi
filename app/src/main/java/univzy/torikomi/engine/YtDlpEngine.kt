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
import java.io.File

/** Wraps youtubedl-android. One instance held by the Application. */
class YtDlpEngine(private val app: Application) {

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

    suspend fun version(): String = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() ?: ""
    }

    /** Fetches metadata + formats for a URL. */
    suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
        init()
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
        info.toMediaInfo(url)
    }

    /**
     * Runs a download into [outputDir] (app-private). Returns the produced file.
     * @param outputDir MUST be an exclusive, per-download directory (the produced file is
     *   located by scanning it); sharing it across concurrent downloads is unsafe.
     * @param onProgress (percent 0..100, etaSeconds, rawLine)
     */
    suspend fun download(
        spec: DownloadSpec,
        outputDir: File,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        init()
        outputDir.mkdirs()
        val request = YoutubeDLRequest(spec.sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-o", "${outputDir.absolutePath}/%(title).80s.%(ext)s")
            if (spec.choice.audioExtract) {
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                addOption("-f", spec.choice.formatSelector)
                addOption("--merge-output-format", "mp4")
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
        outputDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: error("Download produced no file")
    }

    private fun VideoInfo.toMediaInfo(sourceUrl: String): MediaInfo {
        val mappedFormats = (formats ?: emptyList()).map { f ->
            val h = f.height.takeIf { it > 0 }
            val hasVideo = f.vcodec != null && f.vcodec != "none"
            val hasAudio = f.acodec != null && f.acodec != "none"
            MediaFormat(
                formatId = f.formatId ?: "",
                kind = when {
                    hasVideo && hasAudio -> MediaKind.MUXED
                    hasVideo -> MediaKind.VIDEO
                    else -> MediaKind.AUDIO
                },
                ext = f.ext ?: "",
                resolution = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null,
                heightPx = h,
                abrKbps = f.abr.takeIf { it > 0 }?.toInt(),
                filesizeApprox = (f.fileSize.takeIf { it > 0 } ?: f.fileSizeApproximate.takeIf { it > 0 }),
                note = f.formatNote ?: f.format ?: (h?.let { "${it}p" } ?: "audio"),
            )
        }
        return MediaInfo(
            sourceUrl = sourceUrl,
            title = title ?: "Untitled",
            uploader = uploader ?: "",
            thumbnail = thumbnail ?: "",
            durationSec = duration,
            isPlaylist = false,
            detectedSite = (extractorKey ?: extractor ?: "").ifBlank { "Media" },
            formats = mappedFormats,
        )
    }
}
