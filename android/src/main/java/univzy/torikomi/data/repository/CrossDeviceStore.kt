package univzy.torikomi.data.repository

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import univzy.torikomi.data.model.AppPreferences
import univzy.torikomi.data.model.LibraryEntry
import univzy.torikomi.data.model.SyncDocument
import univzy.torikomi.data.model.createAudioFolder
import univzy.torikomi.data.model.renameAudioFolder
import univzy.torikomi.data.model.deleteAudioFolder
import univzy.torikomi.data.model.setAudioMembership
import java.io.File
import java.util.UUID

class CrossDeviceStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cross-device.json"))
    private val installationFile = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
    val deviceId: String = if (installationFile.baseFile.exists()) String(installationFile.readFully(), Charsets.UTF_8)
        else UUID.randomUUID().toString().also { id ->
            val stream = installationFile.startWrite()
            try { stream.write(id.toByteArray(Charsets.UTF_8)); installationFile.finishWrite(stream) }
            catch (e: Exception) { installationFile.failWrite(stream); throw e }
        }
    private val initial = if (file.baseFile.exists()) SyncDocument.parse(String(file.readFully(), Charsets.UTF_8))
        else SyncDocument(AppPreferences(language = univzy.torikomi.util.LocaleHelper.getPersistedTag(context).ifBlank { "system" }), 0, deviceId)
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()

    @Synchronized private fun persist(doc: SyncDocument) {
        SyncDocument.parse(doc.toJson().toString())
        val bytes = doc.toJson().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= SyncDocument.MAX_BYTES) { "Sync file exceeds 2 MiB" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = doc
    }
    private fun nextTime() = state.value.nextTime(System.currentTimeMillis())
    @Synchronized fun updatePreferences(change: (AppPreferences) -> AppPreferences) {
        val value = AppPreferences.fromJson(change(state.value.preferences).toJson())
        persist(state.value.copy(preferences = value, updatedAt = nextTime(), deviceId = deviceId))
    }
    @Synchronized fun addLink(url: String, title: String, kind: String = "link", id: String = UUID.randomUUID().toString(), createdAt: Long? = null) {
        if (state.value.entries.any { it.id == id }) return
        require(state.value.entries.size < 5000) { "Library limit reached" }
        val time = nextTime()
        val entry = LibraryEntry(id, SyncDocument.mediaUrl(url), title.take(1000), kind, createdAt ?: time, time, deviceId)
        persist(SyncDocument.parse(state.value.copy(entries = state.value.entries + entry).toJson().toString()))
    }
    @Synchronized fun removeLink(id: String) {
        val time = nextTime()
        persist(state.value.copy(entries = state.value.entries.map {
            if (it.id == id) it.copy(updatedAt = time, deletedAt = time, deviceId = deviceId) else it
        }, memberships = state.value.memberships.map {
            if (it.entryId == id && it.deletedAt == 0L) it.copy(updatedAt = time, deletedAt = time, deviceId = deviceId) else it
        }))
    }
    @Synchronized fun importBackup(backup: SyncDocument) {
        persist(state.value.importBackup(backup, deviceId, System.currentTimeMillis()))
    }

    @Synchronized fun createFolder(name: String) {
        persist(state.value.createAudioFolder(name, UUID.randomUUID().toString(), nextTime(), deviceId))
    }

    @Synchronized fun renameFolder(id: String, name: String) {
        persist(state.value.renameAudioFolder(id, name, nextTime(), deviceId))
    }

    @Synchronized fun deleteFolder(id: String) {
        persist(state.value.deleteAudioFolder(id, nextTime(), deviceId))
    }

    @Synchronized fun setAudioMembership(item: univzy.torikomi.data.model.DownloadHistory, folderId: String, included: Boolean) {
        val id = item.libraryId.ifBlank { "$deviceId:${item.id}" }
        val time = nextTime()
        val entry = LibraryEntry(id, SyncDocument.mediaUrl(item.url), item.title.take(1000), "audio",
            item.downloadDate, time, deviceId, metadata = item.metadata)
        persist(state.value.setAudioMembership(entry, folderId, included, time, deviceId))
    }

    @Synchronized fun exportBackup(history: List<univzy.torikomi.data.model.DownloadHistory>): ByteArray {
        val document = state.value
        val savedIds = document.entries.map { it.id }.toSet()
        val links = history.filter { it.libraryId.ifBlank { "$deviceId:${it.id}" } !in savedIds }.map { item ->
            LibraryEntry(item.libraryId.ifBlank { "$deviceId:${item.id}" }, SyncDocument.mediaUrl(item.url), item.title.take(1000),
                item.downloadType, item.downloadDate, item.downloadDate, deviceId, metadata = item.metadata)
        }
        val backup = SyncDocument.parse(document.copy(entries = document.entries + links).toJson().toString())
        return backup.toJson().toString(2).toByteArray(Charsets.UTF_8).also {
            require(it.size <= SyncDocument.MAX_BYTES) { "Backup exceeds 2 MiB" }
        }
    }
}
