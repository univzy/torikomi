import Foundation

public struct AppLanguage: Codable, Sendable, Identifiable {
    public let code: String
    public let name: String
    public let rtl: Bool
    public var id: String { code }
}
public struct ThemeColors: Codable, Sendable {
    public let background: String
    public let gradientStart: String
    public let gradientEnd: String
    public let panel: String
    public let foreground: String
    public let muted: String
    public let border: String
    public let accent: String
}
public struct ThemePreset: Codable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let group: String
    public let mode: String
    public let swatch: [String]
    public let light: ThemeColors
    public let dark: ThemeColors
}
public enum AppearanceCatalog {
    private struct Catalog: Decodable {
        let languages: [AppLanguage]
        let themes: [ThemePreset]
        let messages: [String: [String: String]]
        let englishKeys: [String: String]
    }
    // A missing generated resource is a packaging error, caught by CI catalog and Swift tests.
    private static let catalog: Catalog = {
        guard let url = Bundle.module.url(forResource: "catalog", withExtension: "json"),
              let data = try? Data(contentsOf: url), let value = try? JSONDecoder().decode(Catalog.self, from: data) else {
            preconditionFailure("Missing or invalid generated appearance catalog")
        }
        return value
    }()
    public static var languages: [AppLanguage] { catalog.languages }
    public static var themes: [ThemePreset] { catalog.themes }
    public static func theme(_ id: String) -> ThemePreset {
        themes.first { $0.id == id } ?? themes.first { $0.id == "violet" }!
    }
    public static func language(_ raw: String, system: String = Locale.current.identifier) -> String {
        let tag = (raw == "system" ? system : raw).replacingOccurrences(of: "_", with: "-").lowercased()
        let base = String(tag.split(separator: "-").first ?? "en")
        if base == "in" { return "id" }
        if base == "zh" { return ["hant", "tw", "hk", "mo"].contains(where: { tag.contains($0) }) ? "zh-TW" : "zh-CN" }
        return languages.first { $0.code.lowercased() == tag }?.code ?? languages.first { $0.code == base }?.code ?? "en"
    }
    public static func text(_ keyOrEnglish: String, language raw: String) -> String {
        let key = catalog.englishKeys[keyOrEnglish] ?? keyOrEnglish
        return catalog.messages[language(raw)]?[key] ?? catalog.messages["en"]?[key] ?? keyOrEnglish
    }
}
