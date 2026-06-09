package univzy.torikomi.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveDownload(
    val id: String,
    val title: String,
    val itemLabel: String,
    val progress: Int,      // 0..100, or -1 for indeterminate
    val statusText: String,
    val terminal: Boolean = false,
)

/** Singleton observed by the UI and mutated by DownloadService. */
object DownloadController {
    private val _active = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val active: StateFlow<List<ActiveDownload>> = _active.asStateFlow()

    fun upsert(item: ActiveDownload) = _active.update { list ->
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it }
        else list + item
    }

    fun update(id: String, progress: Int, statusText: String) = _active.update { list ->
        list.map { if (it.id == id) it.copy(progress = progress, statusText = statusText) else it }
    }

    fun finish(id: String, progress: Int, statusText: String) = _active.update { list ->
        list.map { if (it.id == id) it.copy(progress = progress, statusText = statusText, terminal = true) else it }
    }

    fun remove(id: String) = _active.update { list -> list.filterNot { it.id == id } }

    fun isEmpty(): Boolean = _active.value.isEmpty()
}
