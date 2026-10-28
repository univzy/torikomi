import Foundation
import XCTest
@testable import TorikomiCore

final class PhotoAlbumTests: XCTestCase {
    private let source = URL(string: "https://www.tiktok.com/@user/photo/123")!
    private let data = Data(#"{"title":"Album","photos":["https://cdn.example/one.jpg?token=a","https://cdn.example/two.jpg?token=b"]}"#.utf8)

    func testNormalizesTikTokPhotosAndKeepsShortLinks() throws {
        XCTAssertEqual(try PhotoAlbum.requestURL(source.absoluteString).path, "/@user/video/123")
        XCTAssertEqual(try PhotoAlbum.requestURL("https://vt.tiktok.com/share/").absoluteString, "https://vt.tiktok.com/share/")
        for url in ["http://www.tiktok.com/@user/photo/123", "https://tiktok.com.attacker.example/post", "file:///photo.jpg"] {
            XCTAssertThrowsError(try PhotoAlbum.requestURL(url))
        }
    }

    func testSecondPageDownloadsOnlySecondPhotoAndAllPreservesAlbumOrder() throws {
        let album = try PhotoAlbum.decode(data, sourceURL: source)
        XCTAssertEqual(album.title, "Album")
        XCTAssertEqual(try album.selection(currentPage: 1).map(\.url.absoluteString), ["https://cdn.example/two.jpg?token=b"])
        XCTAssertEqual(try album.selection(currentPage: nil).map(\.url.absoluteString), ["https://cdn.example/one.jpg?token=a", "https://cdn.example/two.jpg?token=b"])
        XCTAssertThrowsError(try album.selection(currentPage: -1))
        XCTAssertThrowsError(try album.selection(currentPage: 2))
    }

    func testDeduplicatesPhotosAndRejectsUnsafeOrEmptyResults() throws {
        let repeated = Data(#"{"photos":["https://cdn.example/a.jpg","https://cdn.example/a.jpg"]}"#.utf8)
        XCTAssertEqual(try PhotoAlbum.decode(repeated, sourceURL: source).photos.count, 1)
        for raw in [#"{"photos":[]}"#, #"{"photos":["file:///private/photo.jpg"]}"#,
                    #"{"photos":["http://cdn.example/a.jpg"]}"#, #"{"photos":["https://u:p@cdn.example/a.jpg"]}"#,
                    #"{"photos":["https://127.0.0.1/a.jpg"]}"#] {
            XCTAssertThrowsError(try PhotoAlbum.decode(Data(raw.utf8), sourceURL: source))
        }
    }

    func testBoundsGallerySizeBeforeDownloadingAnything() throws {
        XCTAssertThrowsError(try PhotoAlbum.decode(Data(repeating: 32, count: 2 * 1024 * 1024 + 1), sourceURL: source))
        let tooMany = try JSONSerialization.data(withJSONObject: ["photos": (0...100).map { "https://cdn.example/\($0).jpg" }])
        XCTAssertThrowsError(try PhotoAlbum.decode(tooMany, sourceURL: source))
    }
}
