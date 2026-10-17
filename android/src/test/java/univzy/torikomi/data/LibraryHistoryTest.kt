package univzy.torikomi.data

import org.junit.Assert.*
import org.junit.Test
import univzy.torikomi.data.model.*

class LibraryHistoryTest {
    private val entry = LibraryEntry("foreign:song", "https://example.com/song", "Song", "audio", 1, 2, "foreign")
    private val document = SyncDocument(AppPreferences(), 1, "foreign", entries = listOf(entry), memberships = listOf(FolderMembership("favorites", entry.id, 2, "foreign")))
    @Test fun importedFavoriteIsVisibleAndRedownloadKeepsPortableIdentity() {
        val remote = libraryHistory(emptyList(), document, "local").single()
        assertEquals("remote", remote.folderUri)
        assertEquals(entry.id, remote.portableId("local"))
        val downloaded = DownloadHistory("local-file", "example", "Song", entry.url, "", "audio", 3, libraryId = entry.id)
        val result = libraryHistory(listOf(downloaded), document, "local")
        assertEquals(1, result.size)
        assertEquals("local-file", result.single().id)
        assertEquals(entry.id, result.single().portableId("local"))
        assertEquals(document.memberships.single().entryId, result.single().portableId("local"))
    }
    @Test fun deletedImportedEntriesStayDeletedAndMetadataSurvivesLocalPersistence() {
        assertTrue(libraryHistory(emptyList(), document.copy(entries = listOf(entry.copy(deletedAt = 3))), "local").isEmpty())
        val local = DownloadHistory("file", "test", "Song", entry.url, "", "audio", 3,
            metadata = MediaMetadata("Artist", "Album", "Album artist", 3, "Pop", 2026, 120.0), mediaUri = "content://example/file", libraryId = entry.id)
        assertEquals(local, downloadHistoryFromJson(local.toJson()))
    }
}
