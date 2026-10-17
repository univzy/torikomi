package univzy.torikomi.data.model

fun DownloadHistory.portableId(deviceId: String) = libraryId.ifBlank { "$deviceId:$id" }

fun libraryHistory(local: List<DownloadHistory>, document: SyncDocument, deviceId: String): List<DownloadHistory> {
    val mapped = local.map { item ->
        val entry = document.entries.firstOrNull { it.id == item.portableId(deviceId) }
            ?: document.entries.filter { it.deletedAt == 0L && it.kind == item.downloadType && it.url == item.url }.maxByOrNull { it.updatedAt }
        if (entry == null) item else item.copy(libraryId = entry.id)
    }
    val ids = mapped.map { it.portableId(deviceId) }.toSet()
    val remote = document.entries.filter { it.kind == "audio" && it.deletedAt == 0L && it.id !in ids }.map { entry ->
        DownloadHistory("remote:${entry.id}", java.net.URI(entry.url).host.orEmpty(), entry.title, entry.url, "", "audio",
            entry.createdAt, folderUri = "remote", libraryId = entry.id, metadata = entry.metadata)
    }
    return mapped + remote
}
