<p align="center">
  <img src="torikomi.png" width="220" alt="Torikomi" />
</p>

<h1 align="center">Torikomi Downloader</h1>

<p align="center">
  <b>A multi-platform media downloader for Android</b>, powered by a plugin-based extension system.
  <br/>
  Built with Kotlin and Jetpack Compose (Material 3).
</p>

<p align="center">
  <img alt="Min SDK"      src="https://img.shields.io/badge/Min%20SDK-24-3DDC84?logo=android&logoColor=white" />
  <img alt="Target SDK"   src="https://img.shields.io/badge/Target%20SDK-35-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin"       src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose"      src="https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="License"      src="https://img.shields.io/badge/License-MIT-blue.svg" />
</p>

---

## ✨ Features

- **Plugin-based extensions** — every platform is a separate, sideloadable APK. Add new platforms without updating the main app.
- **Multi-platform** — TikTok, Douyin, YouTube, Instagram, Twitter / X, Threads, Facebook, Spotify, WhatsApp Status.
- **Share-to-app** — paste a URL or share from any browser / app via Android Share Sheet.
- **DNS-over-HTTPS fallback** — bypasses ISP-level DNS blocking automatically (Cloudflare 1.1.1.1, Google 8.8.8.8) so downloads keep working even when downloader hosts are filtered.
- **Smart update notifications** — checks GitHub Releases on launch and surfaces a Mihon-style notification with `Download` and `What's new` actions.
- **Animated onboarding wizard** — guided setup for permissions and storage on first launch.
- **In-app video preview** — fixed 1280x720 (16:9) preview frame so portrait videos letterbox cleanly without breaking the controls.
- **Custom storage location** — pick any folder via SAF, or use the default Torikomi folder under `Download/`.
- **Download history** — type / platform filters, in-place playback, share, and delete-with-file.
- **Light, dark, and system themes**.

## 🚀 Quick Start (for end users)

1. Download the latest `torikomi-<version>.apk` from the [Releases](https://github.com/univzy/torikomi-kotlin/releases) page.
2. Install it on Android 7.0 (API 24) or newer.
3. Walk through the onboarding wizard:
   - Grant **notification** permission (Android 13+) so download progress and update alerts show up.
   - Grant **install unknown apps** permission so extensions can be installed from inside the app.
   - Choose a **download folder** (default or custom).
4. Open the **Extensions** tab and install the platforms you want (TikTok, Instagram, etc.).
5. Paste or share a URL — pick the quality, hit download.

## 🧱 Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3, Navigation)
- **MVVM** + **StateFlow**
- **DataStore Preferences** for persistent settings & history
- **OkHttp** + custom DNS resolver (system → DoH fallback) for HTTP
- **Coil** for image loading with graceful error fallbacks
- **Android `DownloadManager`** for the actual file transfer
- **`ContentProvider` IPC** for talking to extension APKs

## 🏗️ System Architecture

```
┌────────────────────────────────────────────┐
│            Torikomi App (UI)               │
│  Onboarding → Home → ExtensionScreen       │
│            → DownloadScreen → History      │
└──────────────────┬─────────────────────────┘
                   │ ContentResolver query
                   ▼
┌────────────────────────────────────────────┐
│         Extension APK (sideloaded)         │
│  content://torikomi.extension.<id>/        │
│  scrape?url=...&cfCookies=...              │
│                                            │
│  Discovery via:                            │
│    <intent-filter>                         │
│      action: com.torikomi.extension        │
│              .action.DISCOVERY             │
│    </intent-filter>                        │
└──────────────────┬─────────────────────────┘
                   │ JSON payload
                   ▼
┌────────────────────────────────────────────┐
│           ExtensionRepository              │
│     parse ScrapeResult → DownloadItems     │
└──────────────────┬─────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────┐
│        Android DownloadManager             │
│      queue → progress → completed          │
└────────────────────────────────────────────┘
```

The main app discovers any installed extension that declares an `<intent-filter>` for `com.torikomi.extension.action.DISCOVERY`, so adding a new extension does **not** require re-publishing the main app.

## 📁 Project Structure

```text
torikomi-kotlin/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml      # extension discovery <queries>
│       └── java/univzy/torikomi/
│           ├── data/
│           │   ├── model/           # ScrapeResult, ExtensionInfo, DownloadHistory, JsonMappers
│           │   └── repository/      # ExtensionRepository, HistoryRepository, SettingsRepository
│           ├── service/
│           │   ├── ExtensionBridgeHandler.kt   # ContentProvider IPC client
│           │   └── UpdateChecker.kt            # GitHub Releases → notification
│           ├── ui/
│           │   ├── components/      # reusable widgets (PlatformCard, …)
│           │   ├── screens/         # Onboarding, Home, Download, History, Settings, …
│           │   ├── template/        # ExtensionScreenTemplate + ScreenBlock + BlockRenderer
│           │   ├── theme/           # MaterialTheme tokens
│           │   ├── App.kt           # NavHost + global routing
│           │   └── ViewModels.kt    # all ViewModels in one file
│           ├── util/                # UrlDetectorService and helpers
│           ├── MainActivity.kt      # share intent + permission + update check
│           └── Torikomi.kt          # Application class + notification channels
├── gradle/
├── keys/                            # release keystore (gitignored)
├── keystore.properties              # signing config (gitignored, see below)
├── settings.gradle.kts
└── build.gradle.kts
```

## 🛠️ Build

### Requirements

- Android SDK (compileSdk **35**, minSdk **24**)
- JDK **11** or newer
- The bundled Gradle wrapper (no global Gradle install required)

### Commands

#### Windows (PowerShell)

```powershell
# Debug APK
.\gradlew.bat app:assembleDebug

# Release APK
.\gradlew.bat app:assembleRelease
```

#### macOS / Linux

```bash
# Debug APK
./gradlew app:assembleDebug

# Release APK
./gradlew app:assembleRelease
```

Output: `app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`.

### Signing

Release signing is read from either `keystore.properties` (preferred for local builds) or environment variables (preferred for CI):

`keystore.properties` (local, **not committed**):

```properties
storeFile=../keys/torikomi-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Or via env (CI):

| Env variable                | Maps to                  |
|-----------------------------|--------------------------|
| `TORIKOMI_STOREFILE`        | `signingConfig.storeFile` |
| `TORIKOMI_STOREPASSWORD`    | `signingConfig.storePassword` |
| `TORIKOMI_KEYALIAS`         | `signingConfig.keyAlias` |
| `TORIKOMI_KEYPASSWORD`      | `signingConfig.keyPassword` |

If no signing config is provided, the release build falls back to the debug keystore so it still produces an installable APK.

## 🔌 Extension System

### How It Works

1. The app fetches the extension catalog from
   `https://raw.githubusercontent.com/univzy/torikomi-extensions/master/index.min.json`.
2. The user installs the extensions they want from the **Extensions** tab — each is a small APK (~600 KB – 2.2 MB).
3. Installed extensions are discovered via Android's `<queries>` mechanism: the main app declares
   ```xml
   <queries>
     <intent>
       <action android:name="com.torikomi.extension.action.DISCOVERY" />
     </intent>
   </queries>
   ```
   and every extension exports an activity matching that filter.
4. To scrape a URL, the app calls
   ```
   content://torikomi.extension.<id>/scrape?url=<encoded_url>&cfCookies=<cookies>
   ```
5. The extension returns a JSON `ScrapeResult` that lists downloadable items, thumbnails, etc.

### Supported Platforms

| Platform        | Extension              | Source ID |
|-----------------|------------------------|-----------|
| TikTok          | Musicaldown            | 1001      |
| Douyin          | Douyin                 | 1002      |
| Twitter / X     | SnapSave Twitter       | 1003      |
| Instagram       | SnapSave Instagram     | 1004      |
| YouTube         | YTDown                 | 1007      |
| Facebook        | SnapSave Facebook      | 1006      |
| Threads         | SnapSave Threads       | 1005      |
| Spotify         | Spotmate               | 1008      |
| SoundCloud      | SoundLoadMate          | 1009      |
| WhatsApp Status | WhatsApp Status (local)| 1010      |

Source code for each extension lives in
[`torikomi-source`](https://github.com/univzy/torikomi-source).

## 🧩 Screen Template System

Download screens are composed declaratively from a list of blocks
([`ui/template/`](app/src/main/java/univzy/torikomi/ui/template/)). This
keeps every extension's screen consistent yet customisable.

### Available `ScreenBlock`s

| Block             | Description |
|-------------------|-------------|
| `UrlInput`        | URL input with paste button and save-path hint |
| `SearchButton`    | Fetch / search button with loading state |
| `Thumbnail`       | Scraped thumbnail (configurable height & corner radius) |
| `Title` / `Author`| Media title and uploader |
| `Description`     | Static or dynamic description |
| `VideoPreview`    | In-app preview locked to 16:9 (1280x720) frame |
| `DownloadButtons` | One row per downloadable item |
| `ImageGallery`    | Full image gallery with per-image download |
| `ActiveDownloads` | Running download progress card |
| `CustomText`      | Static text in any of 5 styles |
| `CustomButton`    | Filled or outlined action button |
| `Divider` / `Spacer` | Layout helpers |

### Preset configs

```kotlin
defaultExtensionConfig()       // thumbnail + preview + downloads
minimalExtensionConfig()       // URL input + download buttons only
mediaFocusedExtensionConfig()  // large thumbnail + video preview
galleryExtensionConfig()       // gallery focus for image-heavy posts
audioExtensionConfig()         // square thumbnail + audio downloads
```

### Example

```kotlin
ExtensionScreenTemplate(
    platform = "spotify",
    config   = audioExtensionConfig("Spotify Downloader"),
    onBack   = { navController.popBackStack() },
)

// Or fully custom:
ExtensionScreenTemplate(
    platform = "custom",
    config   = ExtensionScreenConfig(
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = "https://example.com/video"),
            ScreenBlock.SearchButton(label = "Search Media"),
            ScreenBlock.Spacer(16),
            ScreenBlock.Thumbnail(heightDp = 240, cornerDp = 12),
            ScreenBlock.Title,
            ScreenBlock.Divider,
            ScreenBlock.DownloadButtons,
            ScreenBlock.CustomButton(label = "Open in Browser", onClick = { /* … */ }),
        )
    ),
    onBack = { navController.popBackStack() },
)
```

## 🔁 Update Notifications

On every launch, [`UpdateChecker`](app/src/main/java/univzy/torikomi/service/UpdateChecker.kt)
queries `https://api.github.com/repos/univzy/torikomi/releases/latest`,
compares the tag against the installed `versionName`, and posts a system
notification (channel: **App Updates**) when a newer release is available.

The notification has two actions:

- **Download** — opens the GitHub release page in the user's browser
- **What's new** — opens the same page (the body holds the changelog)

Notifications are de-duplicated per release tag via DataStore.

## 🤝 Contributing

PRs are welcome. The codebase tries to keep main-app changes to a minimum
when adding a new platform — most of the work happens in
[`torikomi-source`](https://github.com/univzy/torikomi-source). Please
open an issue first for non-trivial UX changes.

## 📦 Related Repositories

| Repository | Description |
|---|---|
| [`torikomi-kotlin`](https://github.com/univzy/torikomi-kotlin) | This repo — main Android app |
| [`torikomi-source`](https://github.com/univzy/torikomi-source) | Source code for every extension (Kotlin scrapers) |
| [`torikomi-extensions`](https://github.com/univzy/torikomi-extensions) | Extension catalog (`index.json`, icons, signed APKs) |

## 🧑‍💻 Core Developers

[TobyG74](https://github.com/TobyG74) • [arugaz](https://github.com/arugaz) • [nugraizy](https://github.com/nugraizy)

## 📄 License

MIT © Torikomi Developers
