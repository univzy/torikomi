import SwiftUI
import TorikomiCore

struct PhotoAlbumView: View {
    @EnvironmentObject private var store: AppStore
    let album: PhotoAlbum
    @State private var page = 0

    var body: some View {
        VStack(spacing: 12) {
            if !album.title.isEmpty { Text(album.title).font(.headline).lineLimit(2) }
            TabView(selection: $page) {
                ForEach(Array(album.photos.enumerated()), id: \.element.id) { index, photo in
                    AsyncImage(url: photo.url) { phase in
                        switch phase {
                        case .success(let image): image.resizable().scaledToFit()
                        case .failure: Text(store.text("desktop_preview_unavailable")).foregroundStyle(.secondary)
                        default: ProgressView()
                        }
                    }
                    .accessibilityLabel(store.text("media_photo") + " \(index + 1) / \(album.photos.count)")
                    .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: 280)
            HStack {
                if album.photos.count > 1 {
                    Button { page = max(0, page - 1) } label: { Image(systemName: "chevron.backward").frame(width: 44, height: 44) }
                        .disabled(page == 0).accessibilityLabel(store.text("gallery_previous_photo"))
                }
                Spacer()
                Text(store.text("media_photo") + " \(page + 1) / \(album.photos.count)").monospacedDigit()
                Spacer()
                if album.photos.count > 1 {
                    Button { page = min(album.photos.count - 1, page + 1) } label: { Image(systemName: "chevron.forward").frame(width: 44, height: 44) }
                        .disabled(page >= album.photos.count - 1).accessibilityLabel(store.text("gallery_next_photo"))
                }
            }.buttonStyle(.borderless)
            Button {
                if let photos = try? album.selection(currentPage: page) { store.downloadPhotos(photos, sourceURL: album.sourceURL) }
            } label: {
                Label(store.text("home_download_now"), systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.borderedProminent).disabled(store.downloading)
            if album.photos.count > 1 {
                Button {
                    if let photos = try? album.selection(currentPage: nil) { store.downloadPhotos(photos, sourceURL: album.sourceURL) }
                } label: {
                    Label(store.text("hub_download_all") + " (\(album.photos.count))", systemImage: "arrow.down.circle").frame(maxWidth: .infinity, minHeight: 44)
                }.buttonStyle(.bordered).disabled(store.downloading)
            }
        }.padding(.vertical, 8)
    }
}
