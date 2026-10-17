import QuickLook
import SwiftUI
import TorikomiCore

struct MediaLibraryView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var playback: MediaPlayback
    @State private var search = ""
    @State private var kind = "all"
    @State private var downloadedOnly = false
    @State private var photo: URL?
    @State private var removing: LibraryEntry?
    @State private var organizing: LibraryEntry?
    let use: (LibraryEntry) -> Void
    let played: () -> Void
    private var entries: [LibraryEntry] {
        store.entries.filter { (!downloadedOnly || store.localFile($0) != nil) && (kind == "all" || $0.kind == kind) && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.url.localizedCaseInsensitiveContains(search) || ($0.metadata?.artist?.localizedCaseInsensitiveContains(search) == true)) }
    }
    var body: some View {
        List {
            Section {
                Picker(store.text("history_filter_type"), selection: $kind) {
                    Text(store.text("All", "Semua")).tag("all")
                    Text(store.text("Video", "Video")).tag("video")
                    Text(store.text("Photo", "Foto")).tag("image")
                    Text(store.text("Audio", "Audio")).tag("audio")
                    Text(store.text("media_saved_link")).tag("link")
                }
                Toggle(store.text("ios_downloaded_only"), isOn: $downloadedOnly)
            }.listRowBackground(AppPanel())
            if entries.isEmpty {
                ContentUnavailableView(store.text("history_empty_title"), systemImage: "books.vertical", description: Text(store.text("revision_empty_library"))).listRowBackground(AppPanel())
            }
            ForEach(entries) { entry in
                MediaEntryRow(entry: entry, open: { open(entry) }, use: { use(entry) }, organize: { organizing = entry }, remove: { removing = entry })
                    .listRowBackground(AppPanel())
            }
        }.modifier(AppSurface()).navigationTitle(store.text("media_history"))
            .searchable(text: $search, prompt: store.text("Search library", "Cari di pustaka"))
            .quickLookPreview($photo)
            .sheet(item: $organizing) { entry in FolderMembershipView(entry: entry) }
            .confirmationDialog(store.text("Remove this link? JSON backups will include the removal. Media files are kept.", "Hapus tautan ini? Backup JSON akan menyertakan penghapusan. File media tetap disimpan."), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
                Button(store.text("Remove link", "Hapus tautan"), role: .destructive) { if let removing { store.remove(removing) }; removing = nil }
                Button(store.text("Cancel", "Batal"), role: .cancel) { removing = nil }
            }
    }
    private func open(_ entry: LibraryEntry) {
        guard let file = store.localFile(entry) else { use(entry); return }
        if entry.kind == "image" { photo = file }
        else if ["audio", "video"].contains(entry.kind) { playback.play(entry, queue: entries.filter { $0.kind == entry.kind }, store: store); played() }
        else { photo = file }
    }
}

private struct MediaEntryRow: View {
    @EnvironmentObject private var store: AppStore
    let entry: LibraryEntry
    let open: () -> Void
    let use: () -> Void
    let organize: () -> Void
    let remove: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: store.preferences.layout == "compact" ? 4 : 8) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 12) {
                    MediaThumb(file: store.localFile(entry), kind: entry.kind).frame(width: store.preferences.layout == "compact" ? 56 : 80, height: store.preferences.layout == "compact" ? 56 : 80).clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.title).font(.headline).foregroundStyle(.primary).lineLimit(3)
                        if let artist = entry.metadata?.artist { Text(artist).font(.callout).foregroundStyle(.secondary) }
                        Text(URL(string: entry.url)?.host ?? entry.url).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        if let date = store.downloadedDate(entry) {
                            Text(store.text("ios_downloaded") + ": " + date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                        } else { Text(Date(timeIntervalSince1970: Double(entry.createdAt) / 1000), style: .date).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer(minLength: 0)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.accessibilityLabel(store.text(store.localFile(entry) == nil ? "Use link" : "media_open") + ": " + entry.title)
            HStack(spacing: 0) {
                if let file = store.localFile(entry) {
                    ShareLink(item: file) { actionIcon("square.and.arrow.up") }.accessibilityLabel(store.text("Share file", "Bagikan file"))
                } else {
                    Button { store.download(entry.url, entry: entry) } label: { actionIcon("arrow.down.circle") }.disabled(store.downloading).accessibilityLabel(store.text("Download", "Unduh"))
                }
                if entry.kind == "audio" {
                    Button { store.setMembership(entry, folder: "favorites", included: !store.isMember(entry)) } label: { actionIcon(store.isMember(entry) ? "heart.fill" : "heart") }
                        .accessibilityLabel(store.text(store.isMember(entry) ? "media_unfavorite" : "media_favorite"))
                        .accessibilityAddTraits(store.isMember(entry) ? [.isSelected] : [])
                    Button(action: organize) { actionIcon("folder.badge.plus") }.accessibilityLabel(store.text("media_add_folder"))
                }
                Button(action: use) { actionIcon("link") }.accessibilityLabel(store.text("Use link", "Gunakan tautan"))
                Spacer(minLength: 0)
                Button(role: .destructive, action: remove) { actionIcon("trash") }.accessibilityLabel(store.text("Remove link", "Hapus tautan"))
            }
        }.buttonStyle(.borderless).padding(.vertical, store.preferences.layout == "compact" ? 0 : 8)
    }
    private func actionIcon(_ name: String) -> some View { Image(systemName: name).font(.system(size: 18)).frame(width: 44, height: 44) }
}

struct FavoritesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var editing = false
    @State private var editingId: String?
    @State private var name = ""
    @State private var removing: FavoriteFolder?
    let played: () -> Void
    var body: some View {
        List {
            Section {
                NavigationLink { FavoriteEntriesView(folder: "favorites", name: store.text("media_favorites"), played: played) } label: {
                    Label(store.text("media_favorites"), systemImage: "heart.fill")
                }
            }.listRowBackground(AppPanel())
            Section(store.text("media_back_folders")) {
                if store.folders.isEmpty { Text(store.text("media_favorites_note")).foregroundStyle(.secondary) }
                ForEach(store.folders) { folder in
                    HStack {
                        NavigationLink { FavoriteEntriesView(folder: folder.id, name: folder.name, played: played) } label: { Label(folder.name, systemImage: "folder") }
                        Button { editingId = folder.id; name = folder.name; editing = true } label: { Image(systemName: "pencil").font(.system(size: 18)).frame(width: 44, height: 44) }.accessibilityLabel(store.text("media_rename") + ": " + folder.name)
                        Button(role: .destructive) { removing = folder } label: { Image(systemName: "trash").font(.system(size: 18)).frame(width: 44, height: 44) }.accessibilityLabel(store.text("media_delete_folder") + ": " + folder.name)
                    }.buttonStyle(.borderless)
                }
                Button { editingId = nil; name = ""; editing = true } label: { Label(store.text("media_new_folder"), systemImage: "folder.badge.plus").frame(minHeight: 44) }
            }.listRowBackground(AppPanel())
        }.modifier(AppSurface()).navigationTitle(store.text("media_favorites"))
            .alert(store.text(editingId == nil ? "media_new_folder" : "media_rename"), isPresented: $editing) {
                TextField(store.text("media_folder_name"), text: $name)
                Button(store.text("media_save")) { store.saveFolder(id: editingId, name: name) }
                Button(store.text("Cancel", "Batal"), role: .cancel) {}
            }
            .confirmationDialog(store.text("media_delete_folder_confirm"), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
                Button(store.text("media_delete_folder"), role: .destructive) { if let removing { store.removeFolder(removing) }; removing = nil }
                Button(store.text("Cancel", "Batal"), role: .cancel) { removing = nil }
            }
    }
}

private struct FavoriteEntriesView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var playback: MediaPlayback
    @State private var search = ""
    @State private var organizing: LibraryEntry?
    let folder: String
    let name: String
    let played: () -> Void
    private var entries: [LibraryEntry] {
        store.entries.filter { $0.kind == "audio" && store.isMember($0, folder: folder) && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.metadata?.artist?.localizedCaseInsensitiveContains(search) == true) }
    }
    var body: some View {
        List {
            if entries.isEmpty { ContentUnavailableView(store.text("media_all_favorites"), systemImage: "music.note", description: Text(store.text("media_empty_folder"))) }
            ForEach(entries) { entry in
                VStack(alignment: .leading) {
                    Button { playback.play(entry, queue: entries, store: store); played() } label: {
                        HStack {
                            MediaThumb(file: store.localFile(entry), kind: "audio").frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                            VStack(alignment: .leading) { Text(entry.title).foregroundStyle(.primary); if let artist = entry.metadata?.artist { Text(artist).font(.caption).foregroundStyle(.secondary) } }
                            Spacer(minLength: 0)
                            Image(systemName: "play.fill")
                        }.frame(minHeight: 56).contentShape(Rectangle())
                    }.disabled(store.localFile(entry) == nil).accessibilityLabel(store.text("media_play") + ": " + entry.title)
                    if store.localFile(entry) == nil {
                        HStack {
                            Text(store.text("history_file_not_found")).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Button { store.download(entry.url, entry: entry) } label: { Image(systemName: "arrow.down.circle").font(.system(size: 18)).frame(width: 44, height: 44) }.disabled(store.downloading).accessibilityLabel(store.text("Download"))
                        }
                    }
                    HStack {
                        Button { organizing = entry } label: { Image(systemName: "folder.badge.plus").font(.system(size: 18)).frame(width: 44, height: 44) }.accessibilityLabel(store.text("media_add_folder"))
                        Spacer()
                        Button { store.setMembership(entry, folder: folder, included: false) } label: { Image(systemName: "minus.circle").font(.system(size: 18)).frame(width: 44, height: 44) }.accessibilityLabel(store.text("media_remove_folder_track"))
                    }
                }.buttonStyle(.borderless).listRowBackground(AppPanel())
            }
        }.modifier(AppSurface()).navigationTitle(name).searchable(text: $search, prompt: store.text("Search library", "Cari di pustaka"))
            .sheet(item: $organizing) { FolderMembershipView(entry: $0) }
    }
}

private struct FolderMembershipView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let entry: LibraryEntry
    var body: some View {
        NavigationStack {
            List {
                membership("favorites", name: store.text("media_favorites"))
                ForEach(store.folders) { folder in membership(folder.id, name: folder.name) }
            }.modifier(AppSurface()).navigationTitle(store.text("media_add_folder"))
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(store.text("media_done")) { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
    private func membership(_ folder: String, name: String) -> some View {
        let selected = store.isMember(entry, folder: folder)
        return Button { store.setMembership(entry, folder: folder, included: !selected) } label: {
            HStack {
                Image(systemName: selected ? "checkmark.square.fill" : "square").font(.system(size: 18)).frame(width: 44, height: 44)
                Text(name).foregroundStyle(.primary)
                Spacer()
            }.frame(minHeight: 44).contentShape(Rectangle())
        }.accessibilityLabel(name).accessibilityValue(selected ? store.text("theme_selected") : "")
            .accessibilityAddTraits(selected ? [.isSelected] : []).listRowBackground(AppPanel())
    }
}
