package univzy.torikomi.data.repository

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "torikomi_settings")
private val DOWNLOAD_FOLDER_URI_KEY = stringPreferencesKey("download_folder_uri")
private val DOWNLOAD_FOLDER_NAME_KEY = stringPreferencesKey("download_folder_name")
private val LAST_NOTIFIED_UPDATE_TAG_KEY = stringPreferencesKey("last_notified_update_tag")
private val ONBOARDING_DONE_KEY = booleanPreferencesKey("onboarding_done")

val MEDIA_TYPE_FOLDERS = mapOf(
    "music" to "MUSIC",
    "audio" to "MUSIC",
    "videos" to "VIDEO",
    "video" to "VIDEO",
    "pictures" to "IMAGES",
    "image" to "IMAGES",
)

data class DownloadPathSettings(
    val folderUri: String = "",  // Tree URI from SAF
    val folderName: String = "", // Display name like "FOLDERKU"
)

data class AppReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
)

class SettingsRepository(private val context: Context) {

    private val client = OkHttpClient()

    suspend fun getDownloadPathSettings(): DownloadPathSettings {
        val prefs = context.settingsDataStore.data.first()
        val folderUri = prefs[DOWNLOAD_FOLDER_URI_KEY] ?: ""
        val folderName = prefs[DOWNLOAD_FOLDER_NAME_KEY] ?: "Select Folder"
        return DownloadPathSettings(folderUri = folderUri, folderName = folderName)
    }

    suspend fun setDownloadFolder(folderUri: String, folderName: String) {
        context.settingsDataStore.edit {
            it[DOWNLOAD_FOLDER_URI_KEY] = folderUri
            it[DOWNLOAD_FOLDER_NAME_KEY] = folderName
        }
    }

    suspend fun clearDownloadFolder() {
        context.settingsDataStore.edit {
            it.remove(DOWNLOAD_FOLDER_URI_KEY)
            it.remove(DOWNLOAD_FOLDER_NAME_KEY)
        }
    }

    suspend fun isOnboardingDone(): Boolean {
        val prefs = context.settingsDataStore.data.first()
        return prefs[ONBOARDING_DONE_KEY] == true
    }

    suspend fun markOnboardingDone() {
        context.settingsDataStore.edit { it[ONBOARDING_DONE_KEY] = true }
    }

    suspend fun fetchLatestRelease(): AppReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/univzy/torikomi/releases/latest")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to fetch release: HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            AppReleaseInfo(
                tagName = json.optString("tag_name"),
                name = json.optString("name"),
                body = json.optString("body"),
                htmlUrl = json.optString("html_url"),
                publishedAt = json.optString("published_at"),
            )
        }
    }

    suspend fun shouldNotifyFor(tagName: String): Boolean {
        if (tagName.isBlank()) return false
        val prefs = context.settingsDataStore.data.first()
        return prefs[LAST_NOTIFIED_UPDATE_TAG_KEY] != tagName
    }

    suspend fun markUpdateNotified(tagName: String) {
        if (tagName.isBlank()) return
        context.settingsDataStore.edit { it[LAST_NOTIFIED_UPDATE_TAG_KEY] = tagName }
    }
}
