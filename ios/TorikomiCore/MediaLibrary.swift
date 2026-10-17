import Foundation

extension SyncDocument {
    public func activeEntryIndex(_ id: String) throws -> Int {
        guard let index = entries.firstIndex(where: { $0.id == id && $0.deletedAt == 0 }) else { throw CancellationError() }
        return index
    }
    public func contains(_ entryId: String, in folderId: String) -> Bool {
        memberships.contains { $0.entryId == entryId && $0.folderId == folderId && $0.deletedAt == 0 }
    }
    public mutating func setMembership(entryId: String, folderId: String, included: Bool, time: Int64, deviceId: String) throws {
        guard entries.contains(where: { $0.id == entryId && $0.kind == "audio" && $0.deletedAt == 0 }),
              folderId == "favorites" || folders.contains(where: { $0.id == folderId && $0.deletedAt == 0 }) else { throw SyncError.invalidData }
        let member = FolderMembership(folderId: folderId, entryId: entryId, time: time, deviceId: deviceId, deleted: !included)
        if let index = memberships.firstIndex(where: { $0.entryId == entryId && $0.folderId == folderId }) { memberships[index] = member }
        else { memberships.append(member) }
        try validate()
    }
    public mutating func saveFolder(id: String? = nil, name: String, time: Int64, deviceId: String) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id {
            guard let index = folders.firstIndex(where: { $0.id == id && $0.deletedAt == 0 }) else { throw SyncError.invalidData }
            folders[index].name = name; folders[index].updatedAt = time; folders[index].deviceId = deviceId
        } else { folders.append(FavoriteFolder(name: name, time: time, deviceId: deviceId)) }
        try validate()
    }
    public mutating func removeFolder(_ id: String, time: Int64, deviceId: String) throws {
        guard let index = folders.firstIndex(where: { $0.id == id && $0.deletedAt == 0 }) else { throw SyncError.invalidData }
        folders[index].deletedAt = time; folders[index].updatedAt = time; folders[index].deviceId = deviceId
        for index in memberships.indices where memberships[index].folderId == id && memberships[index].deletedAt == 0 {
            memberships[index].deletedAt = time; memberships[index].updatedAt = time; memberships[index].deviceId = deviceId
        }
        try validate()
    }
}

public enum PlaybackOrder {
    public static func shuffled(ids: [String], startingWith current: String?) -> [String] {
        if let current, ids.contains(current) { return [current] + ids.filter { $0 != current }.shuffled() }
        return ids.shuffled()
    }
    public static func adjacent(current: String?, ids: [String], forward: Bool, wrap: Bool) -> String? {
        guard !ids.isEmpty else { return nil }
        guard let current, let index = ids.firstIndex(of: current) else { return forward ? ids.first : ids.last }
        let next = index + (forward ? 1 : -1)
        if ids.indices.contains(next) { return ids[next] }
        return wrap ? (forward ? ids.first : ids.last) : nil
    }
}
