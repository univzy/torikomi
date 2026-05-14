package univzy.torikomi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import univzy.torikomi.data.repository.ExtensionRepository
import univzy.torikomi.service.ExtensionBridgeHandler

class Torikomi : Application() {

    companion object {
        const val UPDATE_CHANNEL_ID = "torikomi_updates"
    }

    val bridge: ExtensionBridgeHandler by lazy { ExtensionBridgeHandler(this) }
    val extensionRepo: ExtensionRepository by lazy { ExtensionRepository(this, bridge) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
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
    }
}
