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
import univzy.torikomi.engine.DownloadPaths

private val Context.settingsDataStore by preferencesDataStore(name = "torikomi_settings")
private val DOWNLOAD_FOLDER_URI_KEY = stringPreferencesKey("download_folder_uri")
private val DOWNLOAD_FOLDER_NAME_KEY = stringPreferencesKey("download_folder_name")
private val LAST_NOTIFIED_UPDATE_TAG_KEY = stringPreferencesKey("last_notified_update_tag")
private val ONBOARDING_DONE_KEY = booleanPreferencesKey("onboarding_done")
private val YTDLP_READY_KEY = booleanPreferencesKey("ytdlp_ready")
private val YTDLP_VERSION_KEY = stringPreferencesKey("ytdlp_version")
private val CONVERT_VIDEO_KEY = stringPreferencesKey("convert_video")
private val CONVERT_AUDIO_KEY = stringPreferencesKey("convert_audio")
private val CONVERT_IMAGE_KEY = stringPreferencesKey("convert_image")

val MEDIA_TYPE_FOLDERS: Map<String, String> = DownloadPaths.MEDIA_TYPE_FOLDERS

data class DownloadPathSettings(
    val folderUri: String = "",  // Tree URI from SAF
    val folderName: String = "", // Display name like "FOLDERKU"
)

/** Target formats every finished download is converted to (so it can be re-posted to IG/WA/etc). */
data class ConvertSettings(
    val video: String = "mp4",
    val audio: String = "mp3",
    val image: String = "jpg",
) {
    companion object {
        // HEIC omitted — Android has no simple encoder (needs androidx.heifwriter + GL); add if asked.
        val VIDEO = listOf("mp4", "mkv", "webm", "mov", "avi")
        val AUDIO = listOf("mp3", "m4a", "aac", "opus", "flac", "wav")
        val IMAGE = listOf("jpg", "png", "webp")
    }
}

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

    suspend fun getConvertSettings(): ConvertSettings {
        val prefs = context.settingsDataStore.data.first()
        val d = ConvertSettings()
        return ConvertSettings(
            video = prefs[CONVERT_VIDEO_KEY]?.takeIf { it in ConvertSettings.VIDEO } ?: d.video,
            audio = prefs[CONVERT_AUDIO_KEY]?.takeIf { it in ConvertSettings.AUDIO } ?: d.audio,
            image = prefs[CONVERT_IMAGE_KEY]?.takeIf { it in ConvertSettings.IMAGE } ?: d.image,
        )
    }

    suspend fun setConvertSettings(c: ConvertSettings) {
        context.settingsDataStore.edit {
            it[CONVERT_VIDEO_KEY] = c.video
            it[CONVERT_AUDIO_KEY] = c.audio
            it[CONVERT_IMAGE_KEY] = c.image
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

    suspend fun isYtDlpReady(): Boolean =
        context.settingsDataStore.data.first()[YTDLP_READY_KEY] == true

    suspend fun setYtDlpReady(ready: Boolean, version: String) {
        context.settingsDataStore.edit {
            it[YTDLP_READY_KEY] = ready
            if (version.isNotBlank()) it[YTDLP_VERSION_KEY] = version
            else if (!ready) it.remove(YTDLP_VERSION_KEY)
        }
    }

    suspend fun getYtDlpVersion(): String =
        context.settingsDataStore.data.first()[YTDLP_VERSION_KEY] ?: ""
}
