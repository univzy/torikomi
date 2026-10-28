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
        guard url.scheme == "https", ["tiktok.com", "www.tiktok.com", "vt.tiktok.com", "vm.tiktok.com", "instagram.com", "www.instagram.com"].contains(url.host?.lowercased() ?? "") else {
            throw SyncError.invalidURL
        }
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        if (url.host ?? "").hasSuffix("tiktok.com") { parts.path = parts.path.replacingOccurrences(of: "/photo/", with: "/video/") }
        return parts.url!
    }

    public static func decode(_ data: Data, sourceURL: URL) throws -> PhotoAlbum {
        PhotoAlbum(sourceURL: sourceURL, title: "", photos: [])
    }

    public func selection(currentPage: Int?) throws -> [AlbumPhoto] {
        photos
    }
}
