import AVFoundation
import ImageIO
import UIKit
import TorikomiCore

enum MediaFailure: LocalizedError {
    case downloadSize, unavailable, unsupported
    var errorDescription: String? {
        switch self {
        case .downloadSize: return "ios_download_limit"
        case .unavailable: return "media_codec_error"
        case .unsupported: return "ios_direct_media_only"
        }
    }
}

final class DirectMediaDownload: NSObject, URLSessionDownloadDelegate {
    static let maxBytes = 2 * 1024 * 1024 * 1024
    private let lock = NSLock()
    private var exceeded = false
    var sizeExceeded: Bool { lock.lock(); defer { lock.unlock() }; return exceeded }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        let url = request.url
        completionHandler(url?.scheme?.lowercased() == "https" && url?.user == nil && url?.password == nil ? request : nil)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten >= Int64(Self.maxBytes) || totalBytesExpectedToWrite >= Int64(Self.maxBytes) {
            lock.lock(); exceeded = true; lock.unlock(); downloadTask.cancel()
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

enum LocalMedia {
    private static func string(_ key: AVMetadataKey, in items: [AVMetadataItem]) async -> String? {
        guard let item = AVMetadataItem.metadataItems(from: items, withKey: key, keySpace: .common).first,
              let raw = try? await item.load(.stringValue) else { return nil }
        let text = raw.unicodeScalars.filter { $0.value >= 32 && !(127...159).contains($0.value) }.map(String.init).joined()
        return text.isEmpty ? nil : String(text.prefix(1000))
    }
    static func metadata(_ url: URL) async -> MediaMetadata? {
        let asset = AVURLAsset(url: url)
        let items = (try? await asset.load(.commonMetadata)) ?? []
        let artist = await string(.commonKeyArtist, in: items)
        let album = await string(.commonKeyAlbumName, in: items)
        let seconds = (try? await asset.load(.duration))?.seconds
        let duration = seconds.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        return MediaMetadata(artist: artist, album: album, duration: duration)
    }
    static func artwork(_ url: URL, kind: String, maximumSize: CGFloat) async -> UIImage? {
        if kind == "image" {
            return await Task.detached(priority: .utility) { () -> UIImage? in
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: maximumSize, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil }
                return UIImage(cgImage: image)
            }.value
        }
        let asset = AVURLAsset(url: url)
        if let items = try? await asset.load(.commonMetadata),
           let item = AVMetadataItem.metadataItems(from: items, withKey: AVMetadataKey.commonKeyArtwork, keySpace: .common).first,
           let data = try? await item.load(.dataValue), data.count <= 8 * 1024 * 1024,
           let image = UIImage(data: data) {
            return await image.byPreparingThumbnail(ofSize: CGSize(width: maximumSize, height: maximumSize))
        }
        if kind == "video" {
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: maximumSize, height: maximumSize)
            if let result = try? await generator.image(at: .zero) { return UIImage(cgImage: result.image) }
        }
        return nil
    }
}

@MainActor
final class MediaArtwork: ObservableObject {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>(); cache.totalCostLimit = 24 * 1024 * 1024; return cache
    }()
    @Published var image: UIImage?
    func load(_ url: URL?, kind: String, maximumSize: CGFloat) async {
        image = nil
        guard let url else { return }
        let key = (url.absoluteString + "#\(Int(maximumSize))") as NSString
        if let cached = Self.cache.object(forKey: key) { image = cached; return }
        let loaded = await LocalMedia.artwork(url, kind: kind, maximumSize: maximumSize)
        guard !Task.isCancelled, let loaded else { return }
        Self.cache.setObject(loaded, forKey: key, cost: Int(loaded.size.width * loaded.size.height * 4))
        image = loaded
    }
}
