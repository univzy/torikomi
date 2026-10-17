import SwiftUI
import TorikomiCore

extension Color {
    init(catalogHex: String) {
        let hex = Int(catalogHex.dropFirst(), radix: 16) ?? 0
        self.init(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255)
    }
}

struct AppGradient: View {
    let colors: ThemeColors
    var body: some View {
        LinearGradient(colors: [Color(catalogHex: colors.gradientStart), Color(catalogHex: colors.gradientEnd)], startPoint: .topLeading, endPoint: .bottomTrailing)
            .ignoresSafeArea()
    }
}

struct AppPanel: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let preset = AppearanceCatalog.theme(store.preferences.themeColor)
        Color(catalogHex: (scheme == .dark ? preset.dark : preset.light).panel)
    }
}

struct AppSurface: ViewModifier {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        let preset = AppearanceCatalog.theme(store.preferences.themeColor)
        let colors = scheme == .dark ? preset.dark : preset.light
        content.scrollContentBackground(.hidden)
            .background { AppGradient(colors: colors) }
            .foregroundStyle(Color(catalogHex: colors.foreground))
            .toolbarBackground(.hidden, for: .navigationBar)
    }
}

struct ThemeSelector: View {
    @EnvironmentObject private var store: AppStore
    @State private var preview = false
    @State private var draft = Preferences()
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text(store.text("Theme")).font(.title2)
                Spacer()
                Button(store.text("theme_preview")) { draft = store.preferences; preview = true }.buttonStyle(.bordered)
            }
            ThemeChoices(preferences: Binding(get: { store.preferences }, set: { value in
                store.update { $0.themeMode = value.themeMode; $0.themeColor = value.themeColor }
            }))
        }.padding(.vertical, 12)
        .sheet(isPresented: $preview) {
            ThemePreview(draft: $draft) {
                store.update { $0.themeMode = draft.themeMode; $0.themeColor = draft.themeColor }
                preview = false
            }
        }
    }
}

private struct ThemeChoices: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var scheme
    @Binding var preferences: Preferences
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Toggle(isOn: Binding(get: { preferences.themeMode == "system" }, set: { preferences.themeMode = $0 ? "system" : scheme == .dark ? "dark" : "light" })) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(store.text("theme_follow_device"))
                    Text(store.text("theme_follow_device_note")).font(.caption).foregroundStyle(.secondary)
                }
            }
            Text(store.text("theme_default_themes")).font(.subheadline.bold())
            swatches(AppearanceCatalog.themes.filter { $0.group == "default" })
            Text(store.text("theme_color_themes")).font(.subheadline.bold())
            swatches(AppearanceCatalog.themes.filter { $0.group == "color" })
        }
    }
    private func swatches(_ presets: [ThemePreset]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 50, maximum: 54), spacing: 10)], alignment: .leading, spacing: 12) {
            ForEach(presets) { preset in
                let selected = preferences.themeColor == preset.id
                let colors = scheme == .dark ? AppearanceCatalog.theme(preferences.themeColor).dark : AppearanceCatalog.theme(preferences.themeColor).light
                let label = preset.group == "default" ? store.text("theme_" + (preset.id == "neutral" ? "light" : preset.id == "slate" ? "dark" : preset.id)) : preset.name
                Button {
                    preferences.themeColor = preset.id
                    if preset.group == "default" { preferences.themeMode = preset.mode }
                } label: {
                    RoundedRectangle(cornerRadius: 9)
                        .fill(LinearGradient(colors: preset.swatch.map { Color(catalogHex: $0) }, startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 50, height: 50)
                        .overlay(RoundedRectangle(cornerRadius: 9).stroke(selected ? Color(catalogHex: colors.accent) : Color(catalogHex: colors.border), lineWidth: selected ? 3 : 1))
                        .overlay(alignment: .topTrailing) {
                            if selected {
                                Image(systemName: "checkmark.circle.fill").symbolRenderingMode(.palette)
                                    .foregroundStyle(Color(catalogHex: colors.background), Color(catalogHex: colors.accent))
                                    .offset(x: 5, y: -5)
                            }
                        }
                }.buttonStyle(.plain).accessibilityLabel(label)
                    .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

private struct ThemePreview: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme
    @Binding var draft: Preferences
    let apply: () -> Void
    private var dark: Bool { draft.themeMode == "dark" || (draft.themeMode == "system" && systemScheme == .dark) }
    private var colors: ThemeColors { dark ? AppearanceCatalog.theme(draft.themeColor).dark : AppearanceCatalog.theme(draft.themeColor).light }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(store.text("theme_preview_note")).foregroundStyle(Color(catalogHex: colors.muted))
                    ThemeChoices(preferences: $draft)
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Torikomi").font(.title.bold())
                        Text(store.text("Keep something worth saving."))
                        Text("https://example.com/video.mp4").font(.caption).foregroundStyle(Color(catalogHex: colors.muted))
                        Text(store.text("Download")).padding(.horizontal, 18).padding(.vertical, 12).background(Color(catalogHex: colors.accent), in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(Color(catalogHex: colors.background))
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(20).background(Color(catalogHex: colors.panel), in: RoundedRectangle(cornerRadius: 16))
                    Button(store.text("theme_apply"), action: apply).buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                }.padding(24)
            }.background { AppGradient(colors: colors) }.foregroundStyle(Color(catalogHex: colors.foreground))
                .tint(Color(catalogHex: colors.accent))
                .navigationTitle(store.text("theme_preview_title")).navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(.hidden, for: .navigationBar)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(store.text("Close")) { dismiss() } } }
        }.preferredColorScheme(draft.themeMode == "system" ? nil : dark ? .dark : .light)
    }
}
