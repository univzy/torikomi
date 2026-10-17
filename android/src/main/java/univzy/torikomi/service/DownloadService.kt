package univzy.torikomi.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.data.repository.HistoryRepository
import univzy.torikomi.data.repository.SettingsRepository
import univzy.torikomi.engine.ActiveDownload
import univzy.torikomi.engine.DownloadController
import univzy.torikomi.engine.DownloadPublisher
import univzy.torikomi.engine.ImageConverter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that runs yt-dlp downloads. One job per enqueue; survives backgrounding.
 * Start via [enqueue].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(0)
    private val activeJobs = AtomicInteger(0)

    companion object {
        private const val NOTIF_ID = 4242
        private const val EX_URL = "url"
        private const val EX_TITLE = "title"
        private const val EX_THUMB = "thumb"
        private const val EX_SITE = "site"
        private const val EX_LABEL = "label"
        private const val EX_TYPE = "type"
        private const val EX_SELECTOR = "selector"
        private const val EX_AUDIO = "audioExtract"

        fun enqueue(context: Context, spec: DownloadSpec) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EX_URL, spec.sourceUrl)
                putExtra(EX_TITLE, spec.title)
                putExtra(EX_THUMB, spec.thumbnail)
                putExtra(EX_SITE, spec.detectedSite)
                putExtra(EX_LABEL, spec.choice.label)
                putExtra(EX_TYPE, spec.choice.type)
                putExtra(EX_SELECTOR, spec.choice.formatSelector)
                putExtra(EX_AUDIO, spec.choice.audioExtract)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(getString(R.string.notif_preparing))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (intent != null) startJob(intent)
        return START_NOT_STICKY
    }

    private fun startJob(intent: Intent) {
        val spec = DownloadSpec(
            sourceUrl = intent.getStringExtra(EX_URL).orEmpty(),
            title = intent.getStringExtra(EX_TITLE).orEmpty(),
            thumbnail = intent.getStringExtra(EX_THUMB).orEmpty(),
            detectedSite = intent.getStringExtra(EX_SITE).orEmpty(),
            choice = QualityChoice(
                label = intent.getStringExtra(EX_LABEL).orEmpty(),
                type = intent.getStringExtra(EX_TYPE).orEmpty(),
                formatSelector = intent.getStringExtra(EX_SELECTOR).orEmpty(),
                audioExtract = intent.getBooleanExtra(EX_AUDIO, false),
            ),
        )
        if (spec.sourceUrl.isBlank()) { maybeStop(); return }

        val app = applicationContext as Torikomi
        val historyRepo = HistoryRepository(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val id = "${System.currentTimeMillis()}-${idCounter.incrementAndGet()}"
        val outDir = File(cacheDir, "ytdlp/$id")

        DownloadController.upsert(
            ActiveDownload(
                id,
                spec.title.ifBlank { getString(R.string.notif_default_title) },
                spec.choice.label,
                -1,
                getString(R.string.notif_status_starting),
                sourceUrl = spec.sourceUrl,
            )
        )
        activeJobs.incrementAndGet()

        scope.launch {
            try {
                val convert = settingsRepo.getConvertSettings()
                val downloaded = app.ytDlpEngine.download(spec, outDir, id, convert) { percent, _, _ ->
                    val p = percent.toInt().coerceIn(0, 100)
                    DownloadController.update(id, p, getString(R.string.notif_status_downloading_pct, p))
                    updateNotification(getString(R.string.notif_progress, spec.title.ifBlank { getString(R.string.notif_downloading) }, p))
                }
                DownloadController.update(id, 100, getString(R.string.notif_saving))
                // The chosen type is video/audio, but a photo post yields an image file —
                // classify by the produced extension so it lands in the right folder/history.
                val settings = settingsRepo.getDownloadPathSettings()
                downloaded.forEachIndexed { index, source ->
                val actualType = mediaTypeOf(source, spec.choice.type)
                val file = if (actualType == "image") ImageConverter.convert(source, convert.image) else source
                val sizeBytes = runCatching { file.length() }.getOrDefault(0L)
                val published = DownloadPublisher.publish(applicationContext, file, actualType, settings.folderUri)
                historyRepo.saveDownload(
                    platform = spec.detectedSite,
                    title = spec.title.ifBlank { getString(R.string.history_untitled) } + if (downloaded.size > 1) " (${index + 1}/${downloaded.size})" else "",
                    url = spec.sourceUrl,
                    thumbnailUrl = if (actualType == "image") published.mediaUri else spec.thumbnail,
                    downloadType = actualType,
                    fileCount = 1,
                    folderUri = published.folderUri,
                    fileName = published.fileName,
                    quality = if (spec.choice.audioExtract) "Audio (${convert.audio.uppercase()})" else spec.choice.label,
                    fileSize = sizeBytes,
                    metadata = if (actualType == "audio") univzy.torikomi.engine.readMediaTags(file) else null,
                    mediaUri = published.mediaUri,
                    libraryId = app.crossDeviceStore.state.value.entries.filter { it.deletedAt == 0L && it.kind == actualType && it.url == spec.sourceUrl }.maxByOrNull { it.updatedAt }?.id.orEmpty(),
                )
                }
                DownloadController.finish(id, 100, getString(R.string.notif_completed))
            } catch (c: kotlinx.coroutines.CancellationException) {
                DownloadController.remove(id)
                throw c
            } catch (t: Throwable) {
                android.util.Log.e("DownloadService", "download failed", t)
                DownloadController.finish(id, -1, getString(R.string.notif_failed, t.message?.take(300) ?: getString(R.string.notif_unknown_error)))
            } finally {
                runCatching { outDir.deleteRecursively() }
                activeJobs.decrementAndGet()
                maybeStop()
            }
        }
    }

    /** "image" when the produced file is a picture, else the caller's chosen type. */
    private fun mediaTypeOf(file: File, fallback: String): String =
        if (file.extension.lowercase() in ImageConverter.IMAGE_EXTS)
            "image" else fallback

    private fun maybeStop() {
        if (activeJobs.get() <= 0) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, Torikomi.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
