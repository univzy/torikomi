package univzy.torikomi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import univzy.torikomi.service.UpdateChecker
import univzy.torikomi.ui.App

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1201
    }

    private var sharedUrl: String? = null
    private val updateChecker by lazy { UpdateChecker(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle share intent
        sharedUrl = handleIntent(intent)
        ensureNotificationPermission()

        // Kick off background app-update check. Safe to call regardless of
        // permission state — UpdateChecker no-ops on Android 13+ when
        // POST_NOTIFICATIONS isn't granted yet.
        updateChecker.checkInBackground()

        setContent {
            App(initialSharedUrl = sharedUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-trigger share handling by updating app state
        sharedUrl = handleIntent(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission was just granted — re-run the check so the user gets
            // the notification right after accepting on first launch.
            updateChecker.checkInBackground()
        }
    }

    private fun handleIntent(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS,
            )
        }
    }
}
