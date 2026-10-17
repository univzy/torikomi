import XCTest
@testable import TorikomiCore

final class MediaLibraryTests: XCTestCase {
    func testDownloadCompletionCannotReuseADeletedOrMissingEntry() throws {
        var document = SyncDocument(deviceId: "ios")
        document.entries = [LibraryEntry(id: "link", url: "https://example.com/song.m4a", title: "Song", time: 1, deviceId: "ios")]
        XCTAssertEqual(try document.activeEntryIndex("link"), 0)
        document.entries[0].deletedAt = 2; document.entries[0].updatedAt = 2
        let before = document
        XCTAssertThrowsError(try document.activeEntryIndex("link")) { XCTAssertTrue($0 is CancellationError) }
        XCTAssertThrowsError(try document.activeEntryIndex("missing")) { XCTAssertTrue($0 is CancellationError) }
        XCTAssertEqual(document, before)
        XCTAssertEqual(document.entries.count, 1)
        XCTAssertEqual(document.entries[0].deletedAt, 2)
    }
    func testFolderCRUDAndMembershipTombstonesSurviveBackup() throws {
        var document = SyncDocument(deviceId: "ios")
        document.entries = [LibraryEntry(id: "song", url: "https://example.com/song.m4a", title: "Song", kind: "audio", time: 1, deviceId: "ios")]
        try document.saveFolder(name: "  Daily  ", time: 2, deviceId: "ios")
        let folder = try XCTUnwrap(document.folders.first)
        XCTAssertEqual(folder.name, "Daily")
        try document.setMembership(entryId: "song", folderId: folder.id, included: true, time: 3, deviceId: "ios")
        try document.setMembership(entryId: "song", folderId: "favorites", included: true, time: 4, deviceId: "ios")
        XCTAssertTrue(document.contains("song", in: folder.id))
        try document.saveFolder(id: folder.id, name: "Commute", time: 5, deviceId: "ios")
        let before = document
        try document.removeFolder(folder.id, time: 6, deviceId: "ios")
        XCTAssertFalse(document.contains("song", in: folder.id))
        XCTAssertTrue(document.contains("song", in: "favorites"))
        XCTAssertEqual(document.memberships.first?.deletedAt, 6)
        XCTAssertEqual(document.folders.first?.deletedAt, 6)
        XCTAssertEqual(try document.merging(before), document)
        XCTAssertEqual(try SyncDocument.decode(document.encoded()), document)
    }
    func testInvalidFolderNamesAndMembershipTargetsAreRejected() throws {
        let base = SyncDocument(deviceId: "ios")
        for name in ["", " ", "bad\nname", String(repeating: "x", count: 81)] {
            var document = base
            XCTAssertThrowsError(try document.saveFolder(name: name, time: 1, deviceId: "ios"))
        }
        var document = base
        try document.saveFolder(name: "Daily", time: 1, deviceId: "ios")
        XCTAssertThrowsError(try document.saveFolder(name: " Daily ", time: 2, deviceId: "ios"))
        document = base
        document.entries = [LibraryEntry(id: "video", url: "https://example.com/video.mp4", title: "Video", kind: "video", time: 1, deviceId: "ios")]
        XCTAssertThrowsError(try document.setMembership(entryId: "video", folderId: "favorites", included: true, time: 2, deviceId: "ios"))
        XCTAssertThrowsError(try document.setMembership(entryId: "missing", folderId: "favorites", included: true, time: 2, deviceId: "ios"))
    }
    func testMembershipRemovalAndReadditionKeepsOneRevision() throws {
        var document = SyncDocument(deviceId: "ios")
        document.entries = [LibraryEntry(id: "song", url: "https://example.com/song.mp3", title: "Song", kind: "audio", time: 1, deviceId: "ios")]
        try document.setMembership(entryId: "song", folderId: "favorites", included: true, time: 2, deviceId: "ios")
        try document.setMembership(entryId: "song", folderId: "favorites", included: false, time: 3, deviceId: "ios")
        XCTAssertFalse(document.contains("song", in: "favorites"))
        try document.setMembership(entryId: "song", folderId: "favorites", included: true, time: 4, deviceId: "ios")
        XCTAssertTrue(document.contains("song", in: "favorites"))
        XCTAssertEqual(document.memberships.count, 1)
        XCTAssertEqual(document.memberships.first?.updatedAt, 4)
    }
    func testQueueBoundariesWrapAndCurrentSelection() {
        let ids = ["one", "two", "three"]
        XCTAssertEqual(PlaybackOrder.adjacent(current: "two", ids: ids, forward: true, wrap: false), "three")
        XCTAssertEqual(PlaybackOrder.adjacent(current: "two", ids: ids, forward: false, wrap: false), "one")
        XCTAssertNil(PlaybackOrder.adjacent(current: "three", ids: ids, forward: true, wrap: false))
        XCTAssertNil(PlaybackOrder.adjacent(current: "one", ids: ids, forward: false, wrap: false))
        XCTAssertEqual(PlaybackOrder.adjacent(current: "three", ids: ids, forward: true, wrap: true), "one")
        XCTAssertEqual(PlaybackOrder.adjacent(current: "one", ids: ids, forward: false, wrap: true), "three")
        XCTAssertNil(PlaybackOrder.adjacent(current: nil, ids: [], forward: true, wrap: true))
        XCTAssertEqual(PlaybackOrder.adjacent(current: nil, ids: ["one"], forward: true, wrap: false), "one")
        XCTAssertNil(PlaybackOrder.adjacent(current: "one", ids: ["one"], forward: true, wrap: false))
        XCTAssertEqual(PlaybackOrder.adjacent(current: "one", ids: ["one"], forward: true, wrap: true), "one")
    }
    func testShuffleKeepsCurrentFirstAndVisitsEachQueueItemExactlyOnce() {
        let ids = ["one", "two", "three"]
        for _ in 0..<20 {
            let shuffled = PlaybackOrder.shuffled(ids: ids, startingWith: "two")
            XCTAssertEqual(shuffled.first, "two")
            XCTAssertEqual(Set(shuffled), Set(ids))
            XCTAssertEqual(shuffled.count, ids.count)
        }
        XCTAssertTrue(PlaybackOrder.shuffled(ids: [], startingWith: "missing").isEmpty)
    }
}
