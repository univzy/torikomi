package univzy.torikomi.data

import org.junit.Assert.*
import org.junit.Test
import univzy.torikomi.data.model.*

class AudioLibraryTest {
    private val entry = LibraryEntry("song", "https://example.com/song", "Song", "audio", 1, 1, "device")
    @Test fun foldersAndFavoritesSurviveBackupAndRemovalUsesTombstones() {
        val doc = SyncDocument(AppPreferences(), 1, "device")
            .createAudioFolder("Road trip", "folder", 2, "device")
            .setAudioMembership(entry, "folder", true, 3, "device")
            .setAudioMembership(entry, "favorites", true, 4, "device")
        assertEquals(doc, SyncDocument.parse(doc.toJson().toString()))
        val deleted = doc.deleteAudioFolder("folder", 5, "device")
        assertEquals(5L, deleted.folders.single().deletedAt)
        assertTrue(deleted.memberships.single { it.folderId == "folder" }.deletedAt > 0)
        assertEquals(0L, deleted.memberships.single { it.folderId == "favorites" }.deletedAt)
        val merged = doc.merge(deleted)
        assertEquals(deleted.folders, merged.folders)
        assertEquals(deleted.memberships.associateBy { it.folderId }, merged.memberships.associateBy { it.folderId })
    }
    @Test fun rejectsInvalidNamesAndNonAudioMemberships() {
        val doc = SyncDocument(AppPreferences(), 1, "device").createAudioFolder("Road", "folder", 2, "device")
        assertThrows(IllegalArgumentException::class.java) { doc.createAudioFolder("Road", "second", 3, "device") }
        assertThrows(IllegalArgumentException::class.java) { doc.renameAudioFolder("folder", " ", 3, "device") }
        assertThrows(IllegalArgumentException::class.java) { doc.setAudioMembership(entry.copy(kind = "video"), "folder", true, 3, "device") }
        assertThrows(IllegalArgumentException::class.java) { doc.setAudioMembership(entry, "missing", true, 3, "device") }
    }
}
