import Foundation

public struct Preferences: Codable, Equatable, Sendable {
    public var themeMode = "system"
    public var themeColor = "violet"
    public var fontScale = 1.0
    public var fontFamily = "system"
    public var layout = "comfortable"
    public var language = "system"
    public var notifications = true
    public var glass = false
    public init() {}
    public func validate() throws {
        guard ["system", "light", "dark"].contains(themeMode),
              AppearanceCatalog.themes.contains(where: { $0.id == themeColor }),
              fontScale.isFinite, (1...1.5).contains(fontScale),
              ["system", "sans", "serif", "mono"].contains(fontFamily),
              ["comfortable", "compact"].contains(layout), language.utf16.count <= 35 else { throw SyncError.invalidData }
    }
}
public struct SettingsRevision: Codable, Equatable, Sendable {
    public var updatedAt: Int64
    public var deviceId: String
    public var value: Preferences
    public init(updatedAt: Int64 = 0, deviceId: String, value: Preferences = Preferences()) {
        self.updatedAt = updatedAt; self.deviceId = deviceId; self.value = value
    }
}
public struct MediaMetadata: Codable, Equatable, Sendable {
    public var artist: String?
    public var album: String?
    public var albumArtist: String?
    public var trackNumber: UInt32?
    public var genre: String?
    public var year: UInt32?
    public var duration: Double?
    public init(artist: String? = nil, album: String? = nil, duration: Double? = nil) {
        self.artist = artist; self.album = album; self.duration = duration
    }
    public func validate() throws {
        guard [artist, album, albumArtist, genre].allSatisfy({ value in
            value.map { $0.utf16.count <= 1000 && !$0.unicodeScalars.contains { $0.value < 32 || (127...159).contains($0.value) } } ?? true
        }), trackNumber != 0, year.map({ (1...9999).contains($0) }) ?? true,
            duration.map({ $0.isFinite && (0...9_007_199_254_740_991).contains($0) }) ?? true else { throw SyncError.invalidData }
    }
}
public struct FavoriteFolder: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deviceId: String
    public var deletedAt: Int64
    public init(id: String = UUID().uuidString.lowercased(), name: String, time: Int64, deviceId: String) {
        self.id = id; self.name = name; createdAt = time; updatedAt = time; self.deviceId = deviceId; deletedAt = 0
    }
}
public struct FolderMembership: Codable, Equatable, Sendable {
    public var folderId: String
    public var entryId: String
    public var updatedAt: Int64
    public var deviceId: String
    public var deletedAt: Int64
    public init(folderId: String, entryId: String, time: Int64, deviceId: String, deleted: Bool = false) {
        self.folderId = folderId; self.entryId = entryId; updatedAt = time; self.deviceId = deviceId; deletedAt = deleted ? time : 0
    }
    var key: [String] { [folderId, entryId] }
}
public struct LibraryEntry: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var url: String
    public var title: String
    public var kind: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deviceId: String
    public var deletedAt: Int64
    public var metadata: MediaMetadata?
    public init(id: String = UUID().uuidString.lowercased(), url: String, title: String, kind: String = "link", time: Int64, deviceId: String) {
        self.id = id; self.url = url; self.title = title; self.kind = kind
        self.createdAt = time; self.updatedAt = time; self.deviceId = deviceId; self.deletedAt = 0
    }
}
public enum SyncError: LocalizedError {
    case invalidData, invalidURL, tooLarge, server(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidData: return "Invalid or unsupported backup data. Existing data has been kept."
        case .invalidURL: return "Use a valid HTTP or HTTPS link without embedded credentials. Direct downloads require HTTPS."
        case .tooLarge: return "Backup limit reached (2 MiB / 5,000 entries)."
        case .server(let code): return "Download: HTTP \(code)"
        }
    }
}
public struct SyncDocument: Codable, Equatable, Sendable {
    public static let maxBytes = 2 * 1024 * 1024
    public var schemaVersion = 2
    public var settings: SettingsRevision
    public var entries: [LibraryEntry] = []
    public var folders: [FavoriteFolder] = []
    public var memberships: [FolderMembership] = []
    public init(deviceId: String) { settings = SettingsRevision(deviceId: deviceId) }
    enum CodingKeys: String, CodingKey { case schemaVersion, settings, entries, folders, memberships }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let version = try values.decode(Int.self, forKey: .schemaVersion)
        guard [1, 2].contains(version) else { throw SyncError.invalidData }
        schemaVersion = 2
        settings = try values.decode(SettingsRevision.self, forKey: .settings)
        entries = try values.decode([LibraryEntry].self, forKey: .entries)
        folders = version == 2 ? try values.decode([FavoriteFolder].self, forKey: .folders) : []
        memberships = version == 2 ? try values.decode([FolderMembership].self, forKey: .memberships) : []
        for index in folders.indices { folders[index].name = folders[index].name.trimmingCharacters(in: .whitespacesAndNewlines) }
        try validate()
    }
    public static func mediaURL(_ raw: String) throws -> URL {
        guard raw.count <= 8192, let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
              url.user == nil, url.password == nil else { throw SyncError.invalidURL }
        return url
    }
    public func validate() throws {
        func validTime(_ time: Int64) -> Bool { (0...9_007_199_254_740_991).contains(time) }
        func validStamp(_ time: Int64, _ device: String) -> Bool { validTime(time) && !device.isEmpty && device.utf16.count <= 100 }
        guard schemaVersion == 2, entries.count <= 5000, Set(entries.map(\.id)).count == entries.count,
              folders.count <= 100, memberships.count <= 20000,
              validStamp(settings.updatedAt, settings.deviceId) else { throw SyncError.invalidData }
        try settings.value.validate()
        for entry in entries {
            guard !entry.id.isEmpty, entry.id.utf16.count <= 160, entry.title.utf16.count <= 1000,
                  ["video", "audio", "image", "link"].contains(entry.kind),
                  validStamp(entry.updatedAt, entry.deviceId), entry.createdAt >= 0, entry.deletedAt >= 0,
                  entry.createdAt <= 9_007_199_254_740_991, entry.deletedAt <= 9_007_199_254_740_991 else { throw SyncError.invalidData }
            _ = try Self.mediaURL(entry.url)
            try entry.metadata?.validate()
        }
        guard Set(folders.map(\.id)).count == folders.count,
              Set(folders.filter { $0.deletedAt == 0 }.map(\.name)).count == folders.filter({ $0.deletedAt == 0 }).count,
              Set(memberships.map(\.key)).count == memberships.count else { throw SyncError.invalidData }
        for folder in folders {
            guard !folder.id.isEmpty, folder.id != "favorites", folder.id.utf16.count <= 160,
                  !folder.name.isEmpty, folder.name.utf16.count <= 80,
                  folder.name == folder.name.trimmingCharacters(in: .whitespacesAndNewlines),
                  !folder.name.unicodeScalars.contains(where: { $0.value < 32 || (127...159).contains($0.value) }),
                  validStamp(folder.updatedAt, folder.deviceId), validTime(folder.createdAt), validTime(folder.deletedAt) else { throw SyncError.invalidData }
        }
        let folderIds = Set(folders.map(\.id)).union(["favorites"])
        let audioIds = Set(entries.filter { $0.kind == "audio" }.map(\.id))
        for member in memberships {
            guard folderIds.contains(member.folderId), audioIds.contains(member.entryId),
                  validStamp(member.updatedAt, member.deviceId), validTime(member.deletedAt) else { throw SyncError.invalidData }
        }
    }
    public static func decode(_ data: Data) throws -> SyncDocument {
        guard data.count <= maxBytes else { throw SyncError.tooLarge }
        let value = try JSONDecoder().decode(Self.self, from: data)
        _ = try value.encoded(); return value
    }
    public static func readBackup(_ url: URL) throws -> SyncDocument {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var data = Data()
        while let chunk = try handle.read(upToCount: min(64 * 1024, maxBytes + 1 - data.count)), !chunk.isEmpty {
            data.append(chunk)
            guard data.count <= maxBytes else { throw SyncError.tooLarge }
        }
        return try decode(data)
    }
    public func restoring(_ backup: SyncDocument, deviceId: String, now: Int64) throws -> SyncDocument {
        var restored = try merging(backup)
        restored.settings = SettingsRevision(updatedAt: try restored.nextTime(now), deviceId: deviceId, value: backup.settings.value)
        _ = try restored.encoded()
        return restored
    }
    public func encoded() throws -> Data {
        try validate()
        let data = try JSONEncoder().encode(self)
        guard data.count <= Self.maxBytes else { throw SyncError.tooLarge }
        return data
    }
    public func nextTime(_ now: Int64) throws -> Int64 {
        let newest = ([settings.updatedAt] + entries.map(\.updatedAt) + folders.map(\.updatedAt) + memberships.map(\.updatedAt)).max() ?? 0
        guard newest < 9_007_199_254_740_991, now >= 0, now <= 9_007_199_254_740_991 else { throw SyncError.invalidData }
        return max(now, newest + 1)
    }
    public func merging(_ other: SyncDocument) throws -> SyncDocument {
        try validate(); try other.validate()
        func newer(_ time: Int64, _ device: String, than otherTime: Int64, _ otherDevice: String) -> Bool {
            time > otherTime || (time == otherTime && !device.utf16.lexicographicallyPrecedes(otherDevice.utf16))
        }
        var result = newer(settings.updatedAt, settings.deviceId, than: other.settings.updatedAt, other.settings.deviceId) ? self : other
        var merged = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        for entry in other.entries {
            if let old = merged[entry.id], newer(old.updatedAt, old.deviceId, than: entry.updatedAt, entry.deviceId) { continue }
            merged[entry.id] = entry
        }
        result.entries = merged.values.sorted { $0.id < $1.id }
        var mergedFolders = Dictionary(uniqueKeysWithValues: folders.map { ($0.id, $0) })
        for folder in other.folders {
            if let old = mergedFolders[folder.id], newer(old.updatedAt, old.deviceId, than: folder.updatedAt, folder.deviceId) { continue }
            mergedFolders[folder.id] = folder
        }
        result.folders = mergedFolders.values.sorted { $0.id < $1.id }
        var mergedMembers = Dictionary(uniqueKeysWithValues: memberships.map { ($0.key, $0) })
        for member in other.memberships {
            if let old = mergedMembers[member.key], newer(old.updatedAt, old.deviceId, than: member.updatedAt, member.deviceId) { continue }
            mergedMembers[member.key] = member
        }
        result.memberships = mergedMembers.values.sorted {
            $0.folderId == $1.folderId ? $0.entryId < $1.entryId : $0.folderId < $1.folderId
        }
        _ = try result.encoded(); return result
    }
}
