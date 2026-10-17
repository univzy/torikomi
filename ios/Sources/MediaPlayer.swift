import AVFoundation
import AVKit
import MediaPlayer
import SwiftUI
import TorikomiCore

@MainActor
final class MediaPlayback: ObservableObject {
    let player = AVPlayer()
    @Published private(set) var current: LibraryEntry?
    @Published private(set) var queue: [LibraryEntry] = []
    @Published private(set) var playing = false
    @Published private(set) var position = 0.0
    @Published private(set) var duration = 0.0
    @Published var error = ""
    @Published var shuffle = UserDefaults.standard.bool(forKey: "player.shuffle") { didSet { UserDefaults.standard.set(shuffle, forKey: "player.shuffle"); rebuildOrder() } }
    @Published var repeatMode = UserDefaults.standard.string(forKey: "player.repeat") ?? "off" { didSet { UserDefaults.standard.set(repeatMode, forKey: "player.repeat") } }
    @Published var volume: Double = UserDefaults.standard.object(forKey: "player.volume") as? Double ?? 1 { didSet { player.volume = Float(volume); UserDefaults.standard.set(volume, forKey: "player.volume") } }
    @Published var muted = UserDefaults.standard.bool(forKey: "player.muted") { didSet { player.isMuted = muted; UserDefaults.standard.set(muted, forKey: "player.muted") } }
    private var files: [String: URL] = [:]
    private var order: [String] = []
    var orderedQueue: [LibraryEntry] {
        let entries = Dictionary(uniqueKeysWithValues: queue.map { ($0.id, $0) })
        return order.compactMap { entries[$0] }
    }
    private var endObserver: NSObjectProtocol?
    private var periodic: Any?
    private var itemObserver: NSKeyValueObservation?
    private var rateObserver: NSKeyValueObservation?
    init() {
        player.volume = Float(volume); player.isMuted = muted
        periodic = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                self.position = time.seconds.isFinite ? max(0, time.seconds) : 0
                let seconds = self.player.currentItem?.duration.seconds ?? 0
                self.duration = seconds.isFinite ? max(0, seconds) : 0
            }
        }
        rateObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let playing = player.timeControlStatus == .playing
            Task { @MainActor in self?.playing = playing }
        }
    }
    deinit {
        if let periodic { player.removeTimeObserver(periodic) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    }
    func play(_ entry: LibraryEntry, queue entries: [LibraryEntry], store: AppStore) {
        let playable = entries.filter { ["audio", "video"].contains($0.kind) && store.localFile($0) != nil }
        queue = playable
        files = Dictionary(uniqueKeysWithValues: playable.compactMap { item in store.localFile(item).map { (item.id, $0) } })
        rebuildOrder(startingWith: entry.id)
        load(entry.id)
    }
    private func rebuildOrder(startingWith id: String? = nil) {
        let ids = queue.map(\.id)
        guard shuffle else { order = ids; return }
        order = PlaybackOrder.shuffled(ids: ids, startingWith: id ?? current?.id)
    }
    private func load(_ id: String) {
        guard let entry = queue.first(where: { $0.id == id }), let url = files[id] else { error = MediaFailure.unavailable.localizedDescription; return }
        do { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default); try AVAudioSession.sharedInstance().setActive(true) }
        catch { self.error = error.localizedDescription; return }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        let item = AVPlayerItem(url: url)
        current = entry; error = ""; position = 0; duration = entry.metadata?.duration ?? 0
        player.replaceCurrentItem(with: item)
        itemObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            let message = item.error?.localizedDescription ?? MediaFailure.unavailable.localizedDescription
            Task { @MainActor in self?.error = message }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.repeatMode == "one" { self.seek(0); self.player.play() }
                else { self.advance(forward: true, automatic: true) }
            }
        }
        player.play()
    }
    func toggle() {
        if playing { player.pause() }
        else { if duration > 0 && position >= duration - 0.2 { seek(0) }; player.play() }
    }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }
        let value = min(max(0, seconds), max(0, duration))
        position = value; player.seek(to: CMTime(seconds: value, preferredTimescale: 600))
    }
    func advance(forward: Bool, automatic: Bool = false) {
        guard let id = PlaybackOrder.adjacent(current: current?.id, ids: order, forward: forward, wrap: repeatMode == "all") else {
            if automatic { player.pause() }; return
        }
        load(id)
    }
    func canAdvance(_ forward: Bool) -> Bool { PlaybackOrder.adjacent(current: current?.id, ids: order, forward: forward, wrap: repeatMode == "all") != nil }
    func select(_ entry: LibraryEntry) { load(entry.id) }
    func reconcile(_ store: AppStore) {
        let active = Dictionary(uniqueKeysWithValues: store.entries.map { ($0.id, $0) })
        queue = queue.compactMap { active[$0.id] }.filter { store.localFile($0) != nil }
        order = order.filter { id in queue.contains { $0.id == id } }
        let updatedFiles = Dictionary(uniqueKeysWithValues: queue.compactMap { entry in store.localFile(entry).map { (entry.id, $0) } })
        let changedFile = current.map { files[$0.id] != updatedFiles[$0.id] } ?? false
        files = updatedFiles
        if let current {
            guard let refreshed = queue.first(where: { $0.id == current.id }) else { stop(); return }
            self.current = refreshed
            if changedFile { load(current.id) }
        }
    }
    func stop() {
        player.pause(); player.replaceCurrentItem(with: nil); current = nil; queue = []; order = []; files = [:]
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
        itemObserver = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

struct MediaThumb: View {
    @StateObject private var artwork = MediaArtwork()
    let file: URL?
    let kind: String
    var large = false
    var body: some View {
        Group {
            if let image = artwork.image { Image(uiImage: image).resizable().scaledToFill() }
            else { Rectangle().fill(.quaternary).overlay { Image(systemName: kind == "audio" ? "music.note" : kind == "video" ? "film" : kind == "image" ? "photo" : "link").foregroundStyle(.secondary) } }
        }.clipped().task(id: file) { await artwork.load(file, kind: kind, maximumSize: large ? 960 : 240) }.accessibilityHidden(true)
    }
}

struct PlayerView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var playback: MediaPlayback
    var body: some View {
        List {
            if let current = playback.current {
                Section {
                    if current.kind == "video" { VideoPlayer(player: playback.player).frame(minHeight: 240) }
                    else { MediaThumb(file: store.localFile(current), kind: current.kind, large: true).aspectRatio(1, contentMode: .fit).frame(maxHeight: 320).frame(maxWidth: .infinity) }
                    Text(current.title).font(.title2.bold()).textSelection(.enabled)
                    if let artist = current.metadata?.artist { Text(artist).foregroundStyle(.secondary) }
                    if let album = current.metadata?.album { Text(album).font(.callout).foregroundStyle(.secondary) }
                    VStack {
                        Slider(value: Binding(get: { min(playback.position, max(1, playback.duration)) }, set: { playback.seek($0) }), in: 0...max(1, playback.duration))
                            .disabled(playback.duration <= 0).accessibilityLabel(store.text("media_seek"))
                        HStack { Text(clock(playback.position)); Spacer(); Text(clock(playback.duration)) }.font(.caption.monospacedDigit())
                    }
                    HStack(spacing: 0) {
                        control("shuffle", "media_shuffle", active: playback.shuffle) { playback.shuffle.toggle() }
                        Spacer(minLength: 0)
                        control("backward.end.fill", "media_previous") { playback.advance(forward: false) }.disabled(!playback.canAdvance(false))
                        control(playback.playing ? "pause.fill" : "play.fill", playback.playing ? "media_pause" : "media_play") { playback.toggle() }
                        control("forward.end.fill", "media_next") { playback.advance(forward: true) }.disabled(!playback.canAdvance(true))
                        Spacer(minLength: 0)
                        control(playback.repeatMode == "one" ? "repeat.1" : "repeat", "media_repeat", active: playback.repeatMode != "off") {
                            playback.repeatMode = playback.repeatMode == "off" ? "all" : playback.repeatMode == "all" ? "one" : "off"
                        }.accessibilityValue(store.text(playback.repeatMode == "off" ? "ios_repeat_off" : playback.repeatMode == "one" ? "media_repeat_one" : "media_repeat"))
                    }
                    HStack {
                        control(playback.muted ? "speaker.slash.fill" : "speaker.wave.2.fill", playback.muted ? "media_unmute" : "media_mute", active: playback.muted) { playback.muted.toggle() }
                        Slider(value: $playback.volume, in: 0...1).accessibilityLabel(store.text("ios_app_volume"))
                        Text("\(Int(playback.volume * 100))%").font(.caption.monospacedDigit()).frame(minWidth: 40)
                    }
                    Text(store.text("ios_app_volume")).font(.caption).foregroundStyle(.secondary)
                    Text(store.text("ios_system_volume")).font(.caption).foregroundStyle(.secondary)
                    SystemVolume().frame(height: 44).accessibilityLabel(store.text("ios_system_volume"))
                    if !playback.error.isEmpty { Text(store.text(playback.error)).foregroundStyle(.red) }
                }.listRowBackground(AppPanel())
                Section(store.text("media_play_queue")) {
                    ForEach(playback.orderedQueue) { entry in
                        Button { playback.select(entry) } label: {
                            HStack {
                                MediaThumb(file: store.localFile(entry), kind: entry.kind).frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading) { Text(entry.title).foregroundStyle(.primary).lineLimit(1); if let artist = entry.metadata?.artist { Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1) } }
                                Spacer()
                                if entry.id == current.id { Image(systemName: "waveform").accessibilityLabel(store.text("media_now_playing")) }
                            }.frame(minHeight: 44).contentShape(Rectangle())
                        }.accessibilityAddTraits(entry.id == current.id ? [.isSelected] : [])
                            .listRowBackground(entry.id == current.id ? AnyView(Color.accentColor.opacity(0.14)) : AnyView(AppPanel()))
                    }
                }
            } else { ContentUnavailableView(store.text("media_queue_empty"), systemImage: "play.circle") }
        }.modifier(AppSurface()).navigationTitle(store.text("media_now_playing"))
    }
    private func control(_ icon: String, _ label: String, active: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44).background(active ? Color.accentColor.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 8)) }
            .buttonStyle(.borderless).accessibilityLabel(store.text(label)).accessibilityAddTraits(active ? [.isSelected] : [])
    }
    private func clock(_ seconds: Double) -> String { let value = Int(seconds.isFinite ? max(0, seconds) : 0); return String(format: "%d:%02d", value / 60, value % 60) }
}

private struct SystemVolume: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { MPVolumeView(frame: .zero) }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}
