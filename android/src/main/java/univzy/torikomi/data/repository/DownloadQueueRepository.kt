package univzy.torikomi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import univzy.torikomi.data.model.QueuedDownload
import univzy.torikomi.data.model.queuedDownloadFromJson
import univzy.torikomi.data.model.toJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.queueDataStore by preferencesDataStore(name = "torikomi_queue")
private val QUEUE_KEY = stringPreferencesKey("queue_json")

class DownloadQueueRepository(private val context: Context) {

    val queueFlow: Flow<List<QueuedDownload>> = context.queueDataStore.data.map { prefs ->
        parse(prefs[QUEUE_KEY])
    }

    suspend fun add(item: QueuedDownload) {
        val current = parse(context.queueDataStore.data.first()[QUEUE_KEY]).toMutableList()
        current.add(item)
        persist(current)
    }

    suspend fun remove(id: String) {
        val current = parse(context.queueDataStore.data.first()[QUEUE_KEY]).filterNot { it.id == id }
        persist(current)
    }

    suspend fun clear() {
        context.queueDataStore.edit { it.remove(QUEUE_KEY) }
    }

    private suspend fun persist(items: List<QueuedDownload>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.queueDataStore.edit { it[QUEUE_KEY] = arr.toString() }
    }

    private fun parse(raw: String?): List<QueuedDownload> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { queuedDownloadFromJson(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }
}
