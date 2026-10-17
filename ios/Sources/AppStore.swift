import Foundation
import SwiftUI
import Security
import UserNotifications
import TorikomiCore
import AVFoundation
import UniformTypeIdentifiers

private enum Keychain {
    static func read(_ account: String) throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "univzy.torikomi", kSecAttrAccount as String: account,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        return result as? Data
    }
    static func write(_ data: Data, account: String) throws {
        let query = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "univzy.torikomi", kSecAttrAccount as String: account] as [String: Any]
        let values = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as [String: Any]
        var status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    static func remove(_ account: String) throws {
        let status = SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "univzy.torikomi", kSecAttrAccount as String: account] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

private final class NotificationPresentation: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

@MainActor
final class AppStore: ObservableObject {
    private static let maxLocalBytes = SyncDocument.maxBytes * 2
    private struct LocalState: Codable { var document: SyncDocument; var files: [String: String] = [:]; var downloadedAt: [String: Int64]? }
    @Published private var local: LocalState
    @Published var status = ""
    @Published var isError = false
    @Published var downloading = false
    @Published var ready = true
    private var downloadTask: Task<Void, Never>?
    private let deviceId: String
    private let file: URL
    private let notificationPresentation = NotificationPresentation()
    var document: SyncDocument { local.document }
    var preferences: Preferences { document.settings.value }
    var entries: [LibraryEntry] { document.entries.filter { $0.deletedAt == 0 }.sorted { $0.createdAt > $1.createdAt } }
    var folders: [FavoriteFolder] { document.folders.filter { $0.deletedAt == 0 }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending } }
    var minimumTextSize: DynamicTypeSize { preferences.fontScale >= 1.4 ? .xxxLarge : preferences.fontScale >= 1.2 ? .xxLarge : preferences.fontScale > 1 ? .xLarge : .xSmall }
    var selectedLanguage: String { AppearanceCatalog.language(preferences.language) }
    func text(_ en: String, _ id: String = "") -> String {
        let translated = AppearanceCatalog.text(en, language: selectedLanguage)
        return selectedLanguage == "id" && translated == en && !id.isEmpty ? id : translated
    }

    init() {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        file = root.appendingPathComponent("library.json")
        let savedId = try? Keychain.read("installation-id")
        let installationId = savedId.flatMap { String(data: $0, encoding: .utf8) } ?? UUID().uuidString.lowercased()
        deviceId = installationId
        local = LocalState(document: SyncDocument(deviceId: installationId))
        UNUserNotificationCenter.current().delegate = notificationPresentation
        do {
            if savedId == nil { try Keychain.write(Data(deviceId.utf8), account: "installation-id") }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: file.path) {
                let handle = try FileHandle(forReadingFrom: file)
                defer { try? handle.close() }
                let data = try handle.read(upToCount: Self.maxLocalBytes + 1) ?? Data()
                guard data.count <= Self.maxLocalBytes else { throw SyncError.tooLarge }
                let loaded = try JSONDecoder().decode(LocalState.self, from: data)
                try loaded.document.validate()
                guard loaded.files.count <= 5000,
                      (loaded.downloadedAt?.count ?? 0) <= 5000,
                      loaded.downloadedAt?.values.allSatisfy({ (0...9_007_199_254_740_991).contains($0) }) ?? true,
                      loaded.files.values.allSatisfy({ !$0.isEmpty && !$0.contains("/") && !$0.contains("\\") && $0 != "." && $0 != ".." }) else { throw SyncError.invalidData }
                local = loaded
            }
        } catch { status = error.localizedDescription; isError = true; ready = false }
        do { try Keychain.remove("webdav") }
        catch { report(error) }
    }
    private func time() throws -> Int64 {
        try document.nextTime(Int64(Date().timeIntervalSince1970 * 1000))
    }
    private func save(_ next: LocalState) throws {
        guard ready else { throw SyncError.invalidData }
        _ = try next.document.encoded()
        let data = try JSONEncoder().encode(next)
        guard data.count <= Self.maxLocalBytes else { throw SyncError.tooLarge }
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        local = next
    }
    func report(_ error: Error) { status = text(error.localizedDescription); isError = true }
    func update(_ change: (inout Preferences) -> Void) {
        var next = local
        change(&next.document.settings.value)
        do {
            next.document.settings.updatedAt = try time(); next.document.settings.deviceId = deviceId
            try save(next)
        } catch { report(error) }
    }
    func addLink(_ raw: String) {
        do { _ = try add(raw, title: try SyncDocument.mediaURL(raw).host ?? raw); status = text("Link saved", "Tautan tersimpan"); isError = false }
        catch { report(error) }
    }
    private func add(_ raw: String, title: String, kind: String = "link") throws -> LibraryEntry {
        let url = try SyncDocument.mediaURL(raw)
        let entry = try LibraryEntry(url: url.absoluteString, title: String(title.prefix(1000)), kind: kind, time: time(), deviceId: deviceId)
        var next = local; next.document.entries.append(entry); try save(next); return entry
    }
    func remove(_ entry: LibraryEntry) {
        var next = local
        guard let index = next.document.entries.firstIndex(where: { $0.id == entry.id }) else { return }
        do {
            let stamp = try time(); next.document.entries[index].updatedAt = stamp
            next.document.entries[index].deletedAt = stamp; next.document.entries[index].deviceId = deviceId
            for index in next.document.memberships.indices where next.document.memberships[index].entryId == entry.id && next.document.memberships[index].deletedAt == 0 {
                next.document.memberships[index].updatedAt = stamp
                next.document.memberships[index].deletedAt = stamp
                next.document.memberships[index].deviceId = deviceId
            }
            try save(next)
        } catch { report(error) }
    }
    func saveFolder(id: String? = nil, name: String) {
        do {
            var next = local
            try next.document.saveFolder(id: id, name: name, time: time(), deviceId: deviceId)
            try save(next)
        } catch { report(error) }
    }
    func removeFolder(_ folder: FavoriteFolder) {
        do {
            var next = local
            try next.document.removeFolder(folder.id, time: time(), deviceId: deviceId)
            try save(next)
        } catch { report(error) }
    }
    func setMembership(_ entry: LibraryEntry, folder: String, included: Bool) {
        do {
            var next = local
            try next.document.setMembership(entryId: entry.id, folderId: folder, included: included, time: time(), deviceId: deviceId)
            try save(next)
        } catch { report(error) }
    }
    func isMember(_ entry: LibraryEntry, folder: String = "favorites") -> Bool { document.contains(entry.id, in: folder) }
    func downloadedDate(_ entry: LibraryEntry) -> Date? {
        if let stamp = local.downloadedAt?[entry.id] { return Date(timeIntervalSince1970: Double(stamp) / 1000) }
        return localFile(entry).flatMap { try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate }
    }
    func backupData() throws -> Data { try document.encoded() }
    func importBackup(_ backup: SyncDocument) {
        do {
            var next = local
            next.document = try document.restoring(backup, deviceId: deviceId, now: time())
            try save(next)
            status = text("Backup imported. Preferences restored and library merged.", "Backup diimpor. Pengaturan dipulihkan dan pustaka digabungkan.")
            isError = false
        } catch { report(error) }
    }
    func backupExported() {
        status = text("Backup exported", "Backup diekspor"); isError = false
    }
    func localFile(_ entry: LibraryEntry) -> URL? {
        guard let name = local.files[entry.id], !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\\") else { return nil }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]), values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
        return url
    }
    func cancelDownload() { downloadTask?.cancel() }
    func download(_ raw: String, entry: LibraryEntry? = nil) {
        guard !downloading else { return }
        downloading = true; status = ""; isError = false
        downloadTask = Task {
            defer { downloading = false; downloadTask = nil }
            do {
                let url = try SyncDocument.mediaURL(raw)
                guard url.scheme?.lowercased() == "https" else { throw SyncError.invalidURL }
                let delegate = DirectMediaDownload()
                let temporary: URL
                let response: URLResponse
                do { (temporary, response) = try await URLSession.shared.download(from: url, delegate: delegate) }
                catch { if delegate.sizeExceeded { throw MediaFailure.downloadSize }; throw error }
                defer { try? FileManager.default.removeItem(at: temporary) }
                guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw SyncError.server((response as? HTTPURLResponse)?.statusCode ?? 0) }
                guard let finalURL = response.url, finalURL.scheme?.lowercased() == "https", finalURL.user == nil, finalURL.password == nil else { throw SyncError.invalidURL }
                let mime = (response.mimeType ?? "").lowercased()
                let type = UTType(mimeType: mime)
                let kind = type?.conforms(to: .audio) == true ? "audio" : type?.conforms(to: .image) == true ? "image" : type?.conforms(to: .movie) == true ? "video" : nil
                guard let kind, mime != "image/svg+xml" else {
                    throw MediaFailure.unsupported
                }
                guard entry == nil || entry?.kind == "link" || entry?.kind == kind else { throw SyncError.invalidData }
                try Task.checkCancellation()
                let bytes = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard bytes > 0, bytes < DirectMediaDownload.maxBytes else { throw MediaFailure.downloadSize }
                let title = (response.suggestedFilename ?? url.lastPathComponent).components(separatedBy: CharacterSet(charactersIn: "/\\")).last ?? "media"
                let filename = UUID().uuidString.lowercased() + "." + (type?.preferredFilenameExtension ?? "media")
                let destination = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(filename)
                try FileManager.default.moveItem(at: temporary, to: destination)
                do {
                    let metadata = kind == "image" ? nil : await LocalMedia.metadata(destination)
                    try Task.checkCancellation()
                    var next = local
                    let stamp = try time()
                    let saved: LibraryEntry
                    if let entry {
                        let index = try next.document.activeEntryIndex(entry.id)
                        next.document.entries[index].kind = kind; next.document.entries[index].title = String(title.prefix(1000))
                        next.document.entries[index].metadata = metadata
                        next.document.entries[index].updatedAt = stamp; next.document.entries[index].deviceId = deviceId
                        saved = next.document.entries[index]
                    } else {
                        var created = LibraryEntry(url: url.absoluteString, title: String(title.prefix(1000)), kind: kind, time: stamp, deviceId: deviceId)
                        created.metadata = metadata; next.document.entries.append(created); saved = created
                    }
                    let oldFile = localFile(saved)
                    next.files[saved.id] = filename
                    if next.downloadedAt == nil { next.downloadedAt = [:] }
                    next.downloadedAt?[saved.id] = Int64(Date().timeIntervalSince1970 * 1000)
                    try save(next)
                    if let oldFile { try? FileManager.default.removeItem(at: oldFile) }
                } catch {
                    try? FileManager.default.removeItem(at: destination)
                    throw error
                }
                status = text("notif_completed")
                if preferences.notifications {
                    let center = UNUserNotificationCenter.current()
                    if (try? await center.requestAuthorization(options: [.alert, .sound])) == true {
                        let content = UNMutableNotificationContent(); content.title = "Torikomi"; content.body = title
                        try? await center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
                    }
                }
            } catch is CancellationError { status = text("Download cancelled", "Unduhan dibatalkan") }
            catch let error as URLError where error.code == .cancelled { status = text("Download cancelled", "Unduhan dibatalkan") }
            catch { report(error) }
        }
    }
}
