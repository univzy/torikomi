import SwiftUI
import UniformTypeIdentifiers
import TorikomiCore

private enum Destination: Hashable { case download, library, favorites, player, settings }

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var playback: MediaPlayback
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var scheme
    @State private var selection: Destination? = .download
    @State private var url = ""
    @State private var album: PhotoAlbum?
    @State private var browsingAlbum = false
    @State private var albumError = ""
    @AppStorage("onboarding.complete") private var onboarded = false
    var body: some View {
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    List(selection: $selection) {
                        Label(store.text("Download", "Unduh"), systemImage: "arrow.down.circle").tag(Destination.download)
                        Label(store.text("media_history"), systemImage: "books.vertical").tag(Destination.library)
                        Label(store.text("Favorites", "Favorit"), systemImage: "heart").tag(Destination.favorites)
                        Label(store.text("media_now_playing"), systemImage: "play.circle").tag(Destination.player)
                        Label(store.text("Settings", "Pengaturan"), systemImage: "slider.horizontal.3").tag(Destination.settings)
                    }.modifier(AppSurface()).navigationTitle("Torikomi")
                } detail: { NavigationStack { destination(selection ?? .download) } }
            } else {
                TabView(selection: $selection) {
                    NavigationStack { destination(.download) }.tabItem { Label(store.text("Download", "Unduh"), systemImage: "arrow.down.circle") }.tag(Destination.download as Destination?)
                    NavigationStack { destination(.library) }.tabItem { Label(store.text("media_history"), systemImage: "books.vertical") }.tag(Destination.library as Destination?)
                    NavigationStack { destination(.favorites) }.tabItem { Label(store.text("Favorites", "Favorit"), systemImage: "heart") }.tag(Destination.favorites as Destination?)
                    NavigationStack { destination(.player) }.tabItem { Label(store.text("media_now_playing"), systemImage: "play.circle") }.tag(Destination.player as Destination?)
                    NavigationStack { destination(.settings) }.tabItem { Label(store.text("Settings", "Pengaturan"), systemImage: "slider.horizontal.3") }.tag(Destination.settings as Destination?)
                }
            }
        }
        .tint(accent)
        .safeAreaInset(edge: .bottom) {
            if !store.status.isEmpty {
                Text(store.status).font(.callout).foregroundStyle(store.isError ? Color.red : Color.primary)
                    .padding().frame(maxWidth: .infinity).background(.regularMaterial)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .disabled(!store.ready)
        .onChange(of: store.document) { _, _ in playback.reconcile(store) }
        .sheet(isPresented: Binding(get: { !onboarded }, set: { if !$0 { onboarded = true } })) {
            NavigationStack {
                List {
                    Section { Text(store.text("Your media, within reach.", "Media Anda, selalu dekat.")).font(.title.bold()) }
                    Label(store.text("Download original file", "Unduh file asli"), systemImage: "arrow.down.circle")
                    Text(store.text("iOS saves the original file from a direct HTTPS media URL. Site pages do not expose format choices here. Export saved links as JSON, then import them on Android or desktop to choose formats with yt-dlp. Keep this app open during downloads."))
                    Label(store.text("Favorites", "Favorit"), systemImage: "heart")
                    Text(store.text("media_favorites_note"))
                    Label(store.text("JSON backup", "Backup JSON"), systemImage: "doc")
                    Text(store.text("revision_backup_note"))
                }.navigationTitle("Torikomi")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(store.text("Continue", "Lanjutkan")) { onboarded = true } } }
            }.interactiveDismissDisabled()
        }
    }
    @ViewBuilder private func destination(_ destination: Destination) -> some View {
        switch destination {
        case .download: downloadView
        case .library: MediaLibraryView(use: { entry in url = entry.url; selection = .download }, played: { selection = .player })
        case .favorites: FavoritesView(played: { selection = .player })
        case .player: PlayerView()
        case .settings: SettingsView()
        }
    }
    private var downloadView: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: "arrow.down.circle.fill").font(.largeTitle).foregroundStyle(accent).accessibilityHidden(true)
                    Text(store.text("Keep something worth saving.", "Simpan yang berarti untuk Anda.")).font(.largeTitle.bold())
                    Text(store.text("Your media, within reach.", "Media Anda, selalu dekat.")).foregroundStyle(.secondary)
                }.padding(.vertical, 12)
            }.listRowBackground(AppPanel())
            Section(store.text("Media link", "Tautan media")) {
                TextField("https://", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityLabel(store.text("Media link", "Tautan media"))
                Button(store.text("Save link for later", "Simpan tautan untuk nanti")) { store.addLink(url) }.disabled(url.isEmpty)
                if (try? PhotoAlbum.requestURL(url)) != nil {
                    Button(store.text("desktop_album_inspect")) { albumError = ""; browsingAlbum = true }
                        .disabled(store.downloading)
                }
            }.listRowBackground(AppPanel())
            if let album {
                Section(store.text("desktop_album_select")) { PhotoAlbumView(album: album).id(album.photos.map(\.id)) }
                    .listRowBackground(AppPanel())
            }
            if album == nil && (try? PhotoAlbum.requestURL(url)) == nil {
            Section(store.text("Available download", "Unduhan tersedia")) {
                Label {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.text("Original file", "File asli")).font(.headline)
                        Text(store.text("Direct HTTPS URL: original format", "URL HTTPS langsung: format asli")).font(.callout).foregroundStyle(.secondary)
                    }
                } icon: { Image(systemName: "doc.badge.arrow.down").accessibilityHidden(true) }
                Button { store.download(url) } label: {
                    Label(store.text("Download original file", "Unduh file asli"), systemImage: "arrow.down.circle.fill").frame(minHeight: 44)
                }.disabled(url.isEmpty || store.downloading)
            }.listRowBackground(AppPanel())
            }
            Section {
                Text(store.text("iOS saves the original file from a direct HTTPS media URL. Site pages do not expose format choices here. Export saved links as JSON, then import them on Android or desktop to choose formats with yt-dlp. Keep this app open during downloads.", "iOS menyimpan file asli dari URL media HTTPS langsung. Pilihan format halaman situs tidak tersedia di sini. Ekspor tautan tersimpan sebagai JSON, lalu impor di Android atau desktop untuk memilih format dengan yt-dlp. Biarkan aplikasi terbuka selama mengunduh.")).font(.callout).foregroundStyle(.secondary)
            }.listRowBackground(AppPanel())
            if store.downloading { Section { ProgressView(store.text("Downloading…", "Mengunduh…")); Button(store.text("Cancel download", "Batalkan unduhan"), role: .cancel) { store.cancelDownload() } }.listRowBackground(AppPanel()) }
        }.modifier(AppSurface()).navigationTitle("Torikomi")
            .onChange(of: url) { _, _ in album = nil }
            .sheet(isPresented: $browsingAlbum) {
                NavigationStack {
                    VStack(spacing: 0) {
                        if !albumError.isEmpty { Text(store.text(albumError)).padding().foregroundStyle(.secondary) }
                        if let source = URL(string: url) {
                            GalleryBrowser(sourceURL: source, resolved: { found in album = found; browsingAlbum = false },
                                           failed: { message in albumError = message })
                        }
                    }
                    .navigationTitle(store.text("desktop_album_inspect"))
                    .toolbar { ToolbarItem(placement: .cancellationAction) {
                        Button(store.text("Close")) { browsingAlbum = false }
                    } }
                }
            }
    }
    private var accent: Color { Color(catalogHex: (scheme == .dark ? AppearanceCatalog.theme(store.preferences.themeColor).dark : AppearanceCatalog.theme(store.preferences.themeColor).light).accent) }
}


private struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var exporting = false
    @State private var importing = false
    @State private var backup: JSONBackupFile?
    @State private var pendingImport: SyncDocument?
    private func preference<T>(_ key: WritableKeyPath<Preferences, T>) -> Binding<T> {
        Binding(get: { store.preferences[keyPath: key] }, set: { value in store.update { $0[keyPath: key] = value } })
    }
    var body: some View {
        Form {
            Section(store.text("Appearance", "Tampilan")) {
                ThemeSelector()
                Picker(store.text("Language", "Bahasa"), selection: preference(\.language)) {
                    Text(store.text("System", "Sistem")).tag("system")
                    ForEach(AppearanceCatalog.languages) { Text($0.name).tag($0.code) }
                    if store.preferences.language == "in" { Text("Bahasa Indonesia").tag("in") }
                }
                Picker(store.text("Font", "Jenis huruf"), selection: preference(\.fontFamily)) {
                    Text(store.text("System", "Sistem")).tag("system"); Text("Sans serif").tag("sans"); Text("Serif").tag("serif"); Text("Monospace").tag("mono")
                }
                VStack(alignment: .leading) {
                    Text(store.text("Text size", "Ukuran teks") + " · \(Int(store.preferences.fontScale * 100))%")
                    Slider(value: preference(\.fontScale), in: 1...1.5, step: 0.1)
                        .accessibilityLabel(store.text("Text size", "Ukuran teks"))
                }
                Picker(store.text("Layout", "Tata letak"), selection: preference(\.layout)) {
                    Text(store.text("Comfortable", "Nyaman")).tag("comfortable"); Text(store.text("Compact", "Ringkas")).tag("compact")
                }
                Toggle(store.text("Download notifications", "Notifikasi unduhan"), isOn: preference(\.notifications))
            }.listRowBackground(AppPanel())
            Section(store.text("JSON backup", "Backup JSON")) {
                Text(store.text("revision_backup_note")).font(.callout).foregroundStyle(.secondary)
                Button {
                    do { backup = JSONBackupFile(data: try store.backupData()); exporting = true }
                    catch { store.report(error) }
                } label: { Label(store.text("Export JSON", "Ekspor JSON"), systemImage: "square.and.arrow.up") }
                Button { importing = true } label: { Label(store.text("Import JSON", "Impor JSON"), systemImage: "square.and.arrow.down") }
            }.listRowBackground(AppPanel())
        }.modifier(AppSurface()).navigationTitle(store.text("Settings", "Pengaturan"))
            .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "torikomi-backup.json") { result in
                switch result {
                case .success: store.backupExported()
                case .failure(let error): if (error as NSError).code != CocoaError.userCancelled.rawValue { store.report(error) }
                }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json], allowsMultipleSelection: false) { result in
                do {
                    guard let url = try result.get().first else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    pendingImport = try SyncDocument.readBackup(url)
                } catch {
                    if (error as NSError).code != CocoaError.userCancelled.rawValue { store.report(error) }
                }
            }
            .confirmationDialog(store.text("Import this backup?", "Impor backup ini?"), isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } }), titleVisibility: .visible) {
                Button(store.text("Restore preferences and merge library", "Pulihkan pengaturan dan gabungkan pustaka")) {
                    if let pendingImport { store.importBackup(pendingImport) }
                    pendingImport = nil
                }
                Button(store.text("Cancel", "Batal"), role: .cancel) { pendingImport = nil }
            } message: {
                Text(store.text("Your media files stay on this device. Library removals in the backup are also merged.", "File media tetap di perangkat ini. Penghapusan pustaka dalam backup juga digabungkan."))
            }
    }
}

private struct JSONBackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw SyncError.invalidData }
        _ = try SyncDocument.decode(data)
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
