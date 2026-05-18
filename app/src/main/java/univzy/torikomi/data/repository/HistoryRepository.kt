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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "torikomi_prefs")
private val HISTORY_KEY = stringPreferencesKey("download_history")
private const val MAX_HISTORY = 100

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
    ) {
        val history = getHistory().toMutableList()
        history.add(
            0,
            DownloadHistory(
                id           = System.currentTimeMillis().toString(),
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
            )
        )
        if (history.size > MAX_HISTORY) history.subList(MAX_HISTORY, history.size).clear()
        persist(history)
    }

    suspend fun deleteHistory(id: String) {
        persist(getHistory().filter { it.id != id })
    }

    /** Deletes the item from history AND removes the actual downloaded file from storage. */
    suspend fun deleteHistoryAndFile(item: DownloadHistory) {
        deleteFile(item)
        persist(getHistory().filter { it.id != item.id })
    }

    /** Deletes all history entries and their corresponding downloaded files. */
    suspend fun clearAllHistoryAndFiles() {
        getHistory().forEach { deleteFile(it) }
        context.dataStore.edit { it.remove(HISTORY_KEY) }
    }

    suspend fun clearAllHistory() {
        context.dataStore.edit { it.remove(HISTORY_KEY) }
    }

    private fun deleteFile(item: DownloadHistory) {
        if (item.fileName.isBlank()) return
        runCatching {
            val mediaTypeFolder = when (item.downloadType) {
                "audio" -> "MUSIC"
                "video" -> "VIDEO"
                else    -> "IMAGES"
            }
            if (item.folderUri == "default" || item.folderUri.isBlank()) {
                val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(storageDir, "Torikomi/$mediaTypeFolder/${item.fileName}")
                if (file.exists()) {
                    file.delete()
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
                        if (displayName.equals(item.fileName, ignoreCase = true)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                            DocumentsContract.deleteDocument(context.contentResolver, docUri)
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
