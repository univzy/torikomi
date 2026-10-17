package univzy.torikomi.data

import org.junit.Assert.*
import org.junit.Test
import univzy.torikomi.data.model.*

class SyncDocumentTest {
    private fun schemaTwo() = SyncDocument.parse(checkNotNull(javaClass.classLoader?.getResourceAsStream("v2.json")).bufferedReader().use { it.readText() })

    @Test fun mergesFolderAndMembershipRevisionsIndependently() {
        val local = schemaTwo()
        val remote = local.copy(
            folders = local.folders.map { it.copy(name = "Renamed", updatedAt = it.updatedAt + 1, deviceId = "remote") },
            memberships = local.memberships.map { if (it.folderId == "favorites") it.copy(updatedAt = it.updatedAt + 100, deletedAt = it.updatedAt + 100) else it.copy(updatedAt = 1, deletedAt = 0) },
        )
        val merged = local.merge(remote)
        assertEquals("Renamed", merged.folders.single().name)
        assertTrue(merged.memberships.all { it.deletedAt > 0 })
        assertEquals(merged, remote.merge(local))
        assertTrue(merged.nextTime(0) > merged.memberships.maxOf { it.updatedAt })
        assertEquals(local.entries, merged.entries)
    }

    @Test fun validatesMetadataAndReferencesAndStripsPrivateArtwork() {
        val local = schemaTwo()
        val raw = local.toJson()
        val metadata = raw.getJSONArray("entries").getJSONObject(0).getJSONObject("metadata")
        metadata.put("coverUrl", "https://private.example/artwork.jpg")
        assertEquals(local, SyncDocument.parse(raw.toString()))
        metadata.put("duration", -1)
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(raw.toString()) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(local.copy(memberships = local.memberships.map { it.copy(entryId = "missing") }).toJson().toString()) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(local.copy(folders = local.folders.map { it.copy(name = " ") }).toJson().toString()) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(local.copy(memberships = local.memberships + local.memberships.first()).toJson().toString()) }
        assertThrows(IllegalArgumentException::class.java) { local.copy(memberships = local.memberships.map { it.copy(updatedAt = 9_007_199_254_740_991) }).nextTime(0) }
    }
    @Test fun preservesSchemaTwoMediaFoldersAndMembershipTombstones() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("v2.json")).bufferedReader().use { it.readText() }
        val doc = SyncDocument.parse(raw)
        assertEquals(2, doc.toJson().getInt("schemaVersion"))
        assertEquals("Fixture artist", doc.toJson().getJSONArray("entries").getJSONObject(0).getJSONObject("metadata").getString("artist"))
        assertEquals(1, doc.toJson().getJSONArray("folders").length())
        assertEquals(1770000000010L, doc.toJson().getJSONArray("memberships").getJSONObject(1).getLong("deletedAt"))
        assertEquals(doc, doc.merge(SyncDocument.parse(raw)))
        assertEquals(doc, SyncDocument.parse(doc.toJson().toString()))
    }
    @Test fun preservesLegacyAndRegionalLanguageTagsInExistingData() {
        listOf("in", "fr-CA", "zh-Hant-HK").forEach { tag ->
            assertEquals(tag, AppPreferences.fromJson(AppPreferences(language = tag).toJson()).language)
        }
    }
    @Test fun catalogThemesAndLanguagesAreAvailableOnEveryClient() {
        assertEquals(24, AppCatalog.languages.size)
        assertEquals(24, AppCatalog.themes.size)
        assertEquals(setOf("ar", "fa"), AppCatalog.languages.filter { it.rtl }.map { it.code }.toSet())
        AppCatalog.themes.forEach { theme ->
            val prefs = AppPreferences(themeColor = theme.id)
            assertEquals(prefs, AppPreferences.fromJson(prefs.toJson()))
        }
        assertThrows(IllegalArgumentException::class.java) { AppPreferences.fromJson(AppPreferences(themeColor = "unknown").toJson()) }
    }
    @Test fun readsTheSharedIosAndDesktopFixture() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("v1.json")).bufferedReader().use { it.readText() }
        val doc = SyncDocument.parse(raw)
        assertEquals("id", doc.preferences.language)
        assertEquals(1.2f, doc.preferences.fontScale)
        assertEquals(2, doc.entries.size)
        assertEquals(2, doc.toJson().getInt("schemaVersion"))
        assertTrue(doc.folders.isEmpty())
        assertTrue(doc.memberships.isEmpty())
        assertTrue(doc.entries.all { it.metadata == null })
        assertEquals(1770000000010L, doc.entries.last().deletedAt)
        assertEquals(doc, SyncDocument.parse(doc.toJson().toString()))
    }
    private fun doc(time: Long = 0, device: String = "a") = SyncDocument(AppPreferences(), time, device)
    private val entry = LibraryEntry("one", "https://example.com/clip.mp4", "Clip", "video", 10, 10, "a")
    @Test fun mergeKeepsLocalRemoteAndTombstones() {
        val local = doc().copy(entries = listOf(entry, entry.copy(id = "two")))
        val remote = doc(20, "b").copy(entries = listOf(entry.copy(updatedAt = 20, deletedAt = 20, deviceId = "b")))
        val merged = local.merge(remote)
        assertEquals(2, merged.entries.size)
        assertEquals(20L, merged.entries.first().deletedAt)
        assertEquals("b", merged.deviceId)
        assertEquals(merged, remote.merge(local))
        assertEquals(merged, merged.merge(local))
        assertEquals(merged, SyncDocument.parse(merged.toJson().toString()))
    }
    @Test fun timestampTiesResolveByDeviceOnBothClients() { assertEquals("z", doc(10, "a").merge(doc(10, "z")).deviceId) }
    @Test fun rejectsUnsafeUrlsFutureVersionsAndInvalidPreferences() {
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.mediaUrl("https://me:password@example.com") }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.mediaUrl("file:///secret") }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(doc().toJson().put("schemaVersion", 3).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppPreferences.fromJson(AppPreferences(fontScale = 9f).toJson()) }
    }
    @Test fun duplicateIdsFailWithoutSilentlyDroppingData() {
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(doc().copy(entries = listOf(entry, entry)).toJson().toString()) }
    }
    @Test fun rejectsCoercedPreferencesAndEntryFields() {
        val raw = doc().toJson()
        raw.getJSONObject("settings").getJSONObject("value").put("notifications", "true")
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(raw.toString()) }
        val wrongTitle = doc().copy(entries = listOf(entry)).toJson()
        wrongTitle.getJSONArray("entries").getJSONObject(0).put("title", 42)
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(wrongTitle.toString()) }
    }
    @Test fun backupImportAppliesOldPreferencesAndKeepsNewerLocalLinks() {
        val local = doc(200).copy(entries = listOf(entry.copy(updatedAt = 200, title = "New title"), entry.copy(id = "local")))
        val backup = doc(10, "b").copy(preferences = AppPreferences(themeMode = "light"), entries = listOf(entry, entry.copy(id = "remote")))
        val restored = local.importBackup(backup, "local-device", 300)
        assertEquals("light", restored.preferences.themeMode)
        assertEquals(300L, restored.updatedAt)
        assertEquals("local-device", restored.deviceId)
        assertEquals(3, restored.entries.size)
        assertEquals("New title", restored.entries.first { it.id == "one" }.title)
        assertEquals(restored, SyncDocument.parse(restored.toJson().toString()))
    }
    @Test fun backupReadIsBoundedAndRejectsInvalidUtf8() {
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.readBackup(java.io.ByteArrayInputStream(ByteArray(SyncDocument.MAX_BYTES + 1))) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { SyncDocument.readBackup(java.io.ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
        assertEquals(doc(), SyncDocument.readBackup(java.io.ByteArrayInputStream(doc().toJson().toString().toByteArray())))
    }
    @Test fun backupImportRejectsOversizedMergeWithoutChangingTheLocalDocument() {
        val local = doc().copy(entries = (0..2499).map { entry.copy(id = "local-$it", title = "x".repeat(800)) })
        val backup = doc().copy(entries = (0..2499).map { entry.copy(id = "remote-$it", title = "x".repeat(800)) })
        assertThrows(IllegalArgumentException::class.java) { local.importBackup(backup, "a", 300) }
        assertEquals(2500, local.entries.size)
        assertEquals(0L, local.updatedAt)
    }
    @Test fun rejectsTrailingGarbageAndKeepsTombstonesOnImport() {
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.parse(doc().toJson().toString() + "garbage") }
        val local = doc().copy(entries = listOf(entry.copy(updatedAt = 20, deletedAt = 20)))
        val restored = local.importBackup(doc().copy(entries = listOf(entry)), "a", 50)
        assertEquals(20L, restored.entries.single().deletedAt)
    }
}
