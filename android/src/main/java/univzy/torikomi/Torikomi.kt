package univzy.torikomi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import univzy.torikomi.engine.YtDlpEngine

class Torikomi : Application() {

    companion object {
        const val UPDATE_CHANNEL_ID = "torikomi_updates"
        const val DOWNLOAD_CHANNEL_ID = "torikomi_downloads"
    }

    val ytDlpEngine: YtDlpEngine by lazy { YtDlpEngine(this) }
    val crossDeviceStore by lazy { univzy.torikomi.data.repository.CrossDeviceStore(this) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        clearLegacyExtensionPrefs()
    }

    private fun clearLegacyExtensionPrefs() {
        // The extension system stored installed-extension ids in this DataStore; it's now unused.
        runCatching {
            val prefsDir = java.io.File(filesDir.parentFile, "datastore")
            java.io.File(prefsDir, "torikomi_ext_prefs.preferences_pb").takeIf { it.exists() }?.delete()
        }
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val updateChannel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a newer Torikomi version is available"
        }
        manager.createNotificationChannel(updateChannel)
        val downloadChannel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows active download progress" }
        manager.createNotificationChannel(downloadChannel)
    }
}
