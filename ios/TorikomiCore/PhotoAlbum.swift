import Foundation

public struct AlbumPhoto: Identifiable, Equatable, Sendable {
    public let url: URL
    public var id: String { url.absoluteString }
}

public struct PhotoAlbum: Equatable, Sendable {
    public let sourceURL: URL
    public let title: String
    public let photos: [AlbumPhoto]

    public static func requestURL(_ raw: String) throws -> URL {
        let url = try SyncDocument.mediaURL(raw)
        guard url.scheme?.lowercased() == "https", ["tiktok.com", "www.tiktok.com", "vt.tiktok.com", "vm.tiktok.com", "instagram.com", "www.instagram.com"].contains(url.host?.lowercased() ?? "") else {
            throw SyncError.invalidURL
        }
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw SyncError.invalidURL }
        parts.scheme = "https"
        if (url.host ?? "").lowercased().hasSuffix("tiktok.com") { parts.path = parts.path.replacingOccurrences(of: "/photo/", with: "/video/") }
        guard let normalized = parts.url else { throw SyncError.invalidURL }
        return normalized
    }

    public static func decode(_ data: Data, sourceURL: URL) throws -> PhotoAlbum {
        _ = try requestURL(sourceURL.absoluteString)
        guard data.count <= 2 * 1024 * 1024,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let urls = value["photos"] as? [String], !urls.isEmpty, urls.count <= 100 else {
            throw PhotoAlbumFailure.unavailable
        }
        var photos: [AlbumPhoto] = []
        var seen = Set<String>()
        for raw in urls {
            let url = try SyncDocument.mediaURL(raw)
            let host = (url.host ?? "").lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            let parts = host.split(separator: ".").compactMap { Int($0) }
            guard url.scheme?.lowercased() == "https", host != "localhost", !host.hasSuffix(".localhost"),
                  !host.hasSuffix(".local"), host != "metadata.google.internal", !host.contains(":"),
                  !(parts.count == 4 && (parts[0] == 0 || parts[0] == 10 || parts[0] == 127 || parts[0] >= 224 ||
                      (parts[0] == 169 && parts[1] == 254) || (parts[0] == 172 && (16...31).contains(parts[1])) ||
                      (parts[0] == 192 && [0, 168].contains(parts[1])) || (parts[0] == 100 && (64...127).contains(parts[1])))) else {
                throw SyncError.invalidURL
            }
            if seen.insert(url.absoluteString).inserted { photos.append(AlbumPhoto(url: url)) }
        }
        return PhotoAlbum(sourceURL: sourceURL, title: String((value["title"] as? String ?? "").prefix(1000)), photos: photos)
    }

    public func selection(currentPage: Int?) throws -> [AlbumPhoto] {
        guard let currentPage else { return photos }
        guard photos.indices.contains(currentPage) else { throw PhotoAlbumFailure.unavailable }
        return [photos[currentPage]]
    }
}

public enum PhotoAlbumFailure: LocalizedError {
    case unavailable
    public var errorDescription: String? { "error_unsupported_link" }
}
