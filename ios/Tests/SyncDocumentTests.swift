import XCTest
@testable import TorikomiCore

final class SyncDocumentTests: XCTestCase {
    private func schemaTwo() throws -> SyncDocument {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try SyncDocument.decode(Data(contentsOf: root.appendingPathComponent("sync/fixtures/v2.json")))
    }
    func testIndependentFolderMembershipMergeAndMetadataValidation() throws {
        let local = try schemaTwo()
        var remote = local
        remote.folders[0].name = "Renamed"; remote.folders[0].updatedAt += 1
        remote.memberships[0].updatedAt += 100; remote.memberships[0].deletedAt = remote.memberships[0].updatedAt
        remote.memberships[1].updatedAt = 1; remote.memberships[1].deletedAt = 0
        let merged = try local.merging(remote)
        XCTAssertEqual(merged.folders.first?.name, "Renamed")
        XCTAssertTrue(merged.memberships.allSatisfy { $0.deletedAt > 0 })
        XCTAssertEqual(merged, try remote.merging(local))
        XCTAssertGreaterThan(try merged.nextTime(0), merged.memberships.map(\.updatedAt).max() ?? 0)
        XCTAssertEqual(merged.entries, local.entries)
        remote = local; remote.entries[0].metadata?.duration = -1
        XCTAssertThrowsError(try remote.encoded())
        remote = local; remote.memberships[0].entryId = "missing"
        XCTAssertThrowsError(try remote.encoded())
        remote = local; remote.memberships.append(remote.memberships[0])
        XCTAssertThrowsError(try remote.encoded())
        remote = local; remote.memberships[0].updatedAt = 9_007_199_254_740_991
        XCTAssertThrowsError(try remote.nextTime(0))
    }
    func testSchemaTwoPreservesMediaFoldersAndMembershipTombstones() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("sync/fixtures/v2.json"))
        let document = try SyncDocument.decode(data)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: document.encoded()) as? [String: Any])
        XCTAssertEqual(json["schemaVersion"] as? Int, 2)
        XCTAssertEqual((json["folders"] as? [[String: Any]])?.count, 1)
        XCTAssertEqual((json["memberships"] as? [[String: Any]])?.last?["deletedAt"] as? Int64, 1770000000010)
        XCTAssertEqual(document, try SyncDocument.decode(document.encoded()))
        XCTAssertEqual(document, try document.merging(SyncDocument.decode(data)))
    }
    func testAppearanceCatalogAndNewThemes() throws {
        XCTAssertEqual(AppearanceCatalog.languages.count, 24)
        XCTAssertEqual(AppearanceCatalog.themes.count, 24)
        XCTAssertEqual(AppearanceCatalog.language("in"), "id")
        XCTAssertEqual(AppearanceCatalog.language("system", system: "zh-Hant-HK"), "zh-TW")
        XCTAssertEqual(AppearanceCatalog.language("fr-CA"), "fr")
        XCTAssertNotEqual(AppearanceCatalog.text("Download", language: "fr"), "Download")
        for theme in AppearanceCatalog.themes {
            XCTAssertTrue(theme.light.gradientStart.hasPrefix("#"))
            XCTAssertTrue(theme.light.gradientEnd.hasPrefix("#"))
            XCTAssertTrue(theme.dark.gradientStart.hasPrefix("#"))
            XCTAssertTrue(theme.dark.gradientEnd.hasPrefix("#"))
            var doc = SyncDocument(deviceId: "a")
            doc.settings.value.themeColor = theme.id
            XCTAssertEqual(try SyncDocument.decode(doc.encoded()).settings.value.themeColor, theme.id)
        }
        var preferences = Preferences(); preferences.themeColor = "unknown"
        XCTAssertThrowsError(try preferences.validate())
    }
    func testSharedAndroidDesktopFixture() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let doc = try SyncDocument.decode(Data(contentsOf: root.appendingPathComponent("sync/fixtures/v1.json")))
        XCTAssertEqual(doc.settings.value.language, "id")
        XCTAssertEqual(doc.settings.value.fontScale, 1.2)
        XCTAssertEqual(doc.entries.count, 2)
        XCTAssertEqual(doc.schemaVersion, 2)
        XCTAssertTrue(doc.folders.isEmpty)
        XCTAssertTrue(doc.memberships.isEmpty)
        XCTAssertTrue(doc.entries.allSatisfy { $0.metadata == nil })
        XCTAssertEqual(doc.entries.last?.deletedAt, 1770000000010)
        XCTAssertEqual(doc, try SyncDocument.decode(doc.encoded()))
    }
    func testMergeKeepsBothDevicesAndDeletion() throws {
        var a = SyncDocument(deviceId: "a"), b = SyncDocument(deviceId: "b")
        let entry = LibraryEntry(id: "one", url: "https://example.com/clip.mp4", title: "Clip", time: 10, deviceId: "a")
        a.entries = [entry, LibraryEntry(id: "two", url: entry.url, title: "Other", time: 10, deviceId: "a")]
        var removed = entry; removed.updatedAt = 20; removed.deletedAt = 20; removed.deviceId = "b"
        b.entries = [removed]; b.settings.updatedAt = 20
        let merged = try a.merging(b)
        XCTAssertEqual(merged.entries.count, 2)
        XCTAssertEqual(merged.entries.first?.deletedAt, 20)
        XCTAssertEqual(merged.settings.deviceId, "b")
        XCTAssertEqual(merged, try b.merging(a))
        XCTAssertEqual(merged, try merged.merging(a))
        XCTAssertEqual(merged, try SyncDocument.decode(merged.encoded()))
    }
    func testRejectsUnsupportedDataAndUnsafeURLs() throws {
        var doc = SyncDocument(deviceId: "a"); doc.schemaVersion = 3
        XCTAssertThrowsError(try doc.encoded())
        XCTAssertThrowsError(try SyncDocument.mediaURL("file:///secret"))
        XCTAssertThrowsError(try SyncDocument.mediaURL("https://me:password@example.com/media.mp4"))
        doc.schemaVersion = 2; doc.settings.value.fontScale = 10
        XCTAssertThrowsError(try doc.encoded())
    }

    func testBackupRestoresOlderPreferencesWithANewLocalRevisionAndMergesLinks() throws {
        var local = SyncDocument(deviceId: "local"), backup = SyncDocument(deviceId: "other")
        local.settings.updatedAt = 100
        local.entries = [LibraryEntry(id: "kept", url: "https://example.com/local.mp4", title: "Local", time: 100, deviceId: "local")]
        backup.settings.updatedAt = 10
        backup.settings.value.themeColor = "ocean"
        var deleted = LibraryEntry(id: "removed", url: "https://example.com/old.mp4", title: "Removed", time: 10, deviceId: "other")
        deleted.deletedAt = 200; deleted.updatedAt = 200
        backup.entries = [deleted]

        let restored = try local.restoring(backup, deviceId: "local", now: 50)

        XCTAssertEqual(restored.settings.value, backup.settings.value)
        XCTAssertEqual(restored.settings.deviceId, "local")
        XCTAssertEqual(restored.settings.updatedAt, 201)
        XCTAssertEqual(Set(restored.entries.map(\.id)), ["kept", "removed"])
        XCTAssertEqual(restored.entries.first { $0.id == "removed" }?.deletedAt, 200)
        XCTAssertEqual(local.settings.updatedAt, 100)
        XCTAssertEqual(try SyncDocument.decode(restored.encoded()), restored)
    }

    func testInvalidBackupDoesNotChangeOriginalAndCannotOverflowRevision() throws {
        let local = SyncDocument(deviceId: "local")
        var backup = local
        backup.settings.value.fontScale = 10
        XCTAssertThrowsError(try local.restoring(backup, deviceId: "local", now: 10))
        XCTAssertEqual(local.settings.value.fontScale, 1)
        backup = local; backup.settings.updatedAt = 9_007_199_254_740_991
        XCTAssertThrowsError(try local.restoring(backup, deviceId: "local", now: 10))
    }

    func testBackupFileReadIsBoundedAndRejectsMalformedJSON() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        defer { try? FileManager.default.removeItem(at: file) }
        let document = SyncDocument(deviceId: "local")
        try document.encoded().write(to: file)
        XCTAssertEqual(try SyncDocument.readBackup(file), document)
        var atLimit = try document.encoded()
        atLimit.append(Data(repeating: 32, count: SyncDocument.maxBytes - atLimit.count))
        try atLimit.write(to: file)
        XCTAssertEqual(try SyncDocument.readBackup(file), document)
        try Data(repeating: 32, count: SyncDocument.maxBytes + 1).write(to: file)
        XCTAssertThrowsError(try SyncDocument.readBackup(file)) { error in
            guard case SyncError.tooLarge = error else { return XCTFail("Expected bounded-read rejection, got \(error)") }
        }
        try Data("{broken".utf8).write(to: file)
        XCTAssertThrowsError(try SyncDocument.readBackup(file))
    }

    func testBackupContainsOnlySharedPreferencesAndLibraryEntries() throws {
        let document = SyncDocument(deviceId: "local")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: document.encoded()) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["schemaVersion", "settings", "entries", "folders", "memberships"])
        XCTAssertNil(json["files"])
        XCTAssertNil(json["credentials"])
    }
}
