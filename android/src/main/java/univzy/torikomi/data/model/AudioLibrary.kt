package univzy.torikomi.data.model

private fun SyncDocument.checked() = SyncDocument.parse(toJson().toString())

fun SyncDocument.createAudioFolder(name: String, id: String, time: Long, device: String): SyncDocument =
    copy(folders = folders + FavoriteFolder(id, name.trim(), time, time, device)).checked()

fun SyncDocument.renameAudioFolder(id: String, name: String, time: Long, device: String): SyncDocument {
    require(folders.any { it.id == id && it.deletedAt == 0L }) { "Folder not found" }
    return copy(folders = folders.map { if (it.id == id) it.copy(name = name.trim(), updatedAt = time, deviceId = device) else it }).checked()
}

fun SyncDocument.deleteAudioFolder(id: String, time: Long, device: String): SyncDocument {
    require(folders.any { it.id == id && it.deletedAt == 0L }) { "Folder not found" }
    return copy(folders = folders.map { if (it.id == id) it.copy(updatedAt = time, deletedAt = time, deviceId = device) else it },
        memberships = memberships.map { if (it.folderId == id && it.deletedAt == 0L) it.copy(updatedAt = time, deletedAt = time, deviceId = device) else it }).checked()
}

fun SyncDocument.setAudioMembership(entry: LibraryEntry, folder: String, included: Boolean, time: Long, device: String): SyncDocument {
    require(entry.kind == "audio") { "Only audio can be added to a music folder" }
    require(folder == "favorites" || folders.any { it.id == folder && it.deletedAt == 0L }) { "Folder not found" }
    val existing = entries.firstOrNull { it.id == entry.id }
    val saved = (existing ?: entry).copy(updatedAt = time, deletedAt = 0, deviceId = device)
    return copy(entries = entries.filterNot { it.id == saved.id } + saved,
        memberships = memberships.filterNot { it.folderId == folder && it.entryId == saved.id } +
            FolderMembership(folder, saved.id, time, device, if (included) 0 else time)).checked()
}
