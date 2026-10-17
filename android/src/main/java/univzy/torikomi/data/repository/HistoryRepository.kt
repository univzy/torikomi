package univzy.torikomi.data.repository

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.data.model.downloadHistoryFromJson
import univzy.torikomi.data.model.toJson
import univzy.torikomi.data.model.portableId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "torikomi_prefs")
private val HISTORY_KEY = stringPreferencesKey("download_history")

class HistoryRepository(private val context: Context) {

    /** Observe all history as a Flow */
    val historyFlow: Flow<List<DownloadHistory>> = context.dataStore.data.map { prefs ->
        parseHistoryJson(prefs[HISTORY_KEY])
    }

    /** One-shot read */
    suspend fun getHistory(): List<DownloadHistory> =
        parseHistoryJson(context.dataStore.data.first()[HISTORY_KEY])

    suspend fun getHistoryByType(type: String): List<DownloadHistory> =
        getHistory().filter { it.downloadType == type }

    suspend fun getHistoryByPlatform(platform: String): List<DownloadHistory> =
        getHistory().filter { it.platform.lowercase() == platform.lowercase() }

    suspend fun saveDownload(
        platform: String,
        title: String,
        url: String,
        thumbnailUrl: String,
        downloadType: String,
        fileCount: Int = 1,
        folderUri: String = "default",
        fileName: String = "",
        quality: String = "",
        fileSize: Long = 0,
        metadata: univzy.torikomi.data.model.MediaMetadata? = null,
        mediaUri: String = "",
        libraryId: String = "",
    ) {
        context.dataStore.edit { prefs ->
        val history = parseHistoryJson(prefs[HISTORY_KEY]).toMutableList()
        history.add(
            0,
            DownloadHistory(
                id           = java.util.UUID.randomUUID().toString(),
                platform     = platform,
                title        = title.ifBlank { "Untitled" },
                url          = url,
                thumbnailUrl = thumbnailUrl,
                downloadType = downloadType,
                downloadDate = System.currentTimeMillis(),
                fileCount    = fileCount,
                folderUri    = folderUri,
                fileName     = fileName,
                quality      = quality,
                fileSize     = fileSize,
                metadata     = metadata,
                mediaUri     = mediaUri,
                libraryId    = libraryId,
            )
        )
        prefs[HISTORY_KEY] = JSONArray().apply { history.forEach { put(it.toJson()) } }.toString()
        }
    }

    suspend fun deleteHistory(id: String) {
        context.dataStore.edit { prefs ->
            prefs[HISTORY_KEY] = JSONArray().apply { parseHistoryJson(prefs[HISTORY_KEY]).filterNot { it.id == id }.forEach { put(it.toJson()) } }.toString()
        }
    }

    /** Deletes the item from history AND removes the actual downloaded file from storage. */
    suspend fun deleteHistoryAndFile(item: DownloadHistory) {
        deleteFile(item)
        deleteHistory(item.id)
        val store = (context.applicationContext as? univzy.torikomi.Torikomi)?.crossDeviceStore
        if (store != null && store.state.value.entries.any { it.id == item.portableId(store.deviceId) }) store.removeLink(item.portableId(store.deviceId))
    }

    /** Deletes all history entries and their corresponding downloaded files. */
    suspend fun clearAllHistoryAndFiles() {
        getHistory().forEach { deleteHistoryAndFile(it) }
    }

    suspend fun clearAllHistory() {
        context.dataStore.edit { it.remove(HISTORY_KEY) }
    }

    private fun deleteFile(item: DownloadHistory) {
        if (item.mediaUri.startsWith("content://")) {
            val uri = Uri.parse(item.mediaUri)
            val deleted = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                else context.contentResolver.delete(uri, null, null) > 0
            check(deleted) { "Could not delete the file" }
            return
        }
        if (item.fileName.isBlank()) return
        run {
            val mediaTypeFolder = when (item.downloadType) {
                "audio" -> "MUSIC"
                "video" -> "VIDEO"
                else    -> "IMAGES"
            }
            if (item.folderUri == "default" || item.folderUri.isBlank()) {
                val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(storageDir, "Torikomi/$mediaTypeFolder/${item.fileName}")
                if (file.exists()) {
                    check(file.delete()) { "Could not delete the file" }
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                }
            } else {
                val treeUri = Uri.parse(item.folderUri)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(1) ?: continue
                        val prefixedName = "${mediaTypeFolder}_${item.fileName}"
                        if (displayName.equals(item.fileName, ignoreCase = true) ||
                            displayName.equals(prefixedName, ignoreCase = true)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                            check(DocumentsContract.deleteDocument(context.contentResolver, docUri)) { "Could not delete the file" }
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun persist(history: List<DownloadHistory>) {
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        context.dataStore.edit { it[HISTORY_KEY] = arr.toString() }
    }

    private fun parseHistoryJson(raw: String?): List<DownloadHistory> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { downloadHistoryFromJson(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }
}
