import SwiftUI
import TorikomiCore

@main
@MainActor
struct TorikomiApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var playback = MediaPlayback()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
                .environmentObject(playback)
                .preferredColorScheme(store.preferences.themeMode == "system" ? nil : store.preferences.themeMode == "dark" ? .dark : .light)
                .fontDesign(store.preferences.fontFamily == "serif" ? .serif : store.preferences.fontFamily == "mono" ? .monospaced : .default)
                .dynamicTypeSize(store.minimumTextSize ... .accessibility5)
                .environment(\.locale, Locale(identifier: store.selectedLanguage))
                .environment(\.layoutDirection, AppearanceCatalog.languages.first { $0.code == store.selectedLanguage }?.rtl == true ? .rightToLeft : .leftToRight)
        }
    }
}
