package univzy.torikomi.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import univzy.torikomi.Torikomi
import univzy.torikomi.data.repository.AppReleaseInfo
import univzy.torikomi.data.repository.SettingsRepository

/**
 * Checks for a newer Torikomi release on GitHub and surfaces it via a system
 * notification (similar to Mihon's "New version available!"), with two actions:
 *
 *  • Download    → opens the release page in the user's browser
 *  • What's new  → opens the same page (which lists the changelog) — kept as
 *                 a separate button so behavior matches the Mihon UI.
 *
 * The notification is shown only once per release tag; the "last notified tag"
 * is persisted via [SettingsRepository.markUpdateNotified].
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        const val NOTIFICATION_ID = 9_001
        private const val ACTION_VIEW_RELEASE = "univzy.torikomi.action.VIEW_RELEASE"
    }

    private val settings = SettingsRepository(context)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Update check failed: ${e.message}")
        }
    )

    /**
     * Kick off a background update check. Safe to call from any thread, runs
     * fully off the main thread and silently no-ops when:
     *  • the device is on Android 13+ but POST_NOTIFICATIONS is not granted
     *  • there is no newer release than the installed version
     *  • the latest release was already shown to the user
     *  • the network call fails for any reason
     */
    fun checkInBackground() {
        scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        val release = runCatching { settings.fetchLatestRelease() }
            .onFailure { Log.w(TAG, "fetchLatestRelease failed: ${it.message}") }
            .getOrNull() ?: return

        val tag = release.tagName.trim()
        if (tag.isBlank()) return

        // Only show if newer than installed and not already shown.
        if (!isNewerThanInstalled(tag)) return
        if (!settings.shouldNotifyFor(tag)) return

        if (!hasNotificationPermission()) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted; skipping update notification")
            return
        }

        showUpdateNotification(release)
        settings.markUpdateNotified(tag)
    }

    private fun isNewerThanInstalled(tag: String): Boolean {
        val installed = currentVersionName() ?: return true   // unknown: assume update is wanted
        val a = parseVersion(tag)
        val b = parseVersion(installed)
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> = v
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .split(".", "-", "_")
        .map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }

    private fun currentVersionName(): String? = runCatching {
        val pkg = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
        info.versionName
    }.getOrNull()

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showUpdateNotification(release: AppReleaseInfo) {
        val title = "New version available!"
        val versionLabel = release.tagName.ifBlank { release.name }
        val whatsNew = release.body.takeIf { it.isNotBlank() }

        val contentIntent = openReleasePageIntent(release.htmlUrl, requestCode = 0)
        val downloadIntent = openReleasePageIntent(release.htmlUrl, requestCode = 1)
        val whatsNewIntent = openReleasePageIntent(release.htmlUrl, requestCode = 2)

        val builder = NotificationCompat.Builder(context, Torikomi.UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(versionLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(whatsNew ?: versionLabel))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.stat_sys_download,
                "Download",
                downloadIntent,
            )

        if (whatsNew != null) {
            builder.addAction(
                android.R.drawable.ic_menu_info_details,
                "What's new",
                whatsNewIntent,
            )
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun openReleasePageIntent(url: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
