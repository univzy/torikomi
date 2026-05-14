<p align="center">
  <img src="torikomi.png" width="250" alt="Torikomi" />
</p>

<h1 align="center">Torikomi Kotlin App</h1>
<p align="center">
    Android application for the Torikomi Downloader, built with Kotlin and Jetpack Compose.
    <br>
    Torikomi is a media downloader app that supports multiple platforms (TikTok, Douyin, YouTube, Instagram, Twitter/X, Threads, Facebook, Spotify) through a separately installable extension system. 
</p>

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **MVVM** + **StateFlow**
- **DataStore Preferences** — persistent settings storage
- **OkHttp** — HTTP client
- **Coil** — image loading
- **ContentProvider IPC** — communication with extension APKs

## System Architecture

```
┌─────────────────────────────────────────┐
│           Torikomi App (UI)             │
│  HomeScreen → ExtensionScreen           │
│  DownloadScreen → HistoryScreen         │
└──────────────┬──────────────────────────┘
               │ ContentProvider IPC
               ▼
┌─────────────────────────────────────────┐
│        Extension APK (separate app)     │
│  content://torikomi.extension.<id>/     │
│  scrape?url=...&cfCookies=...           │
└──────────────┬──────────────────────────┘
               │ JSON payload
               ▼
┌─────────────────────────────────────────┐
│         ExtensionRepository             │
│  parse ScrapeResult → DownloadItems     │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│         DownloadManager Service         │
│  queue → download → notification        │
└─────────────────────────────────────────┘
```

## Project Structure

```text
torikomi/
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/univzy/torikomi/
│       ├── data/
│       │   ├── model/              # Data models, JsonMappers
│       │   └── repository/         # ExtensionRepository, HistoryRepository, SettingsRepository
│       ├── service/                # DownloadManager service
│       ├── ui/
│       │   ├── components/         # PlatformCard and reusable components
│       │   ├── screens/            # HomeScreen, DownloadScreen, HistoryScreen, SettingsScreen
│       │   ├── template/           # ExtensionScreenTemplate, ScreenBlock, BlockRenderer
│       │   ├── theme/              # MaterialTheme, colors, typography
│       │   ├── App.kt              # NavHost and main routing
│       │   └── ViewModels.kt       # ViewModels for all screens
│       ├── util/                   # Helper functions
│       ├── MainActivity.kt
│       └── Torikomi.kt             # Application class
├── gradle/
├── settings.gradle.kts
└── build.gradle.kts
```

## Requirements

- Android SDK (compileSdk 35, minSdk 24)
- JDK 11+
- Gradle wrapper

## Build

Debug APK:

```powershell
.\gradlew.bat app:assembleDebug
```

Release APK:

```powershell
.\gradlew.bat app:assembleRelease
```

## Signing

Release signing can be configured via:

- `keystore.properties` file (local, not committed):
  ```properties
  storeFile=../keys/torikomi.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
- Or environment variables: `TORIKOMI_STOREFILE`, `TORIKOMI_STOREPASSWORD`, `TORIKOMI_KEYALIAS`, `TORIKOMI_KEYPASSWORD`

If no signing config is provided, the release build still runs using the debug keystore.

## Extension System

### How It Works

1. The app loads the extension catalog from:
   ```
   https://raw.githubusercontent.com/univzy/torikomi-extensions/master/index.min.json
   ```
2. Users browse the extension list and install the ones they want.
3. Installed extensions are discovered via `AndroidManifest.xml` metadata (`torikomi.extension = true`).
4. When a user submits a URL, the app forwards the request to the extension via `ContentProvider`:
   ```
   content://torikomi.extension.<id>/scrape?url=<encoded_url>&cfCookies=<cookies>
   ```
5. The extension returns a JSON payload that is parsed into download options.

### Supported Extensions

| Platform | Extension | Source ID |
|---|---|---|
| TikTok | Musicaldown | 1001 |
| Douyin | Douyin | 1018 |
| Twitter/X | SnapSave Twitter | 1012 |
| Instagram | SnapSave Instagram | 1013 |
| YouTube | YTDown | 1014 |
| Facebook | SnapSave Facebook | 1015 |
| Threads | SnapSave Threads | 1016 |
| Spotify | Spotmate Downloader | 1017 |

## Template System

Download screens are built declaratively using a block-based system in `ui/template/`.

### Available Blocks (`ScreenBlock`)

| Block | Description |
|---|---|
| `UrlInput` | URL input field with paste button and save-path hint |
| `SearchButton` | Fetch/search button with loading state |
| `Thumbnail` | Scrape result thumbnail (configurable height & corner radius) |
| `Title` / `Author` | Media title and uploader name |
| `Description` | Static or dynamic description text |
| `VideoPreview` | In-app video player |
| `DownloadButtons` | Download button per item in the scrape result |
| `ImageGallery` | Full image gallery with per-image download buttons |
| `ActiveDownloads` | Running download progress card |
| `CustomText` | Static text (Title / Subtitle / Body / Caption / Label styles) |
| `CustomButton` | Custom filled or outlined action button |
| `Divider` / `Spacer` | Layout helpers |

### Preset Configs

```kotlin
defaultExtensionConfig()        // full: thumbnail + preview + downloads
minimalExtensionConfig()        // URL input + download buttons only
mediaFocusedExtensionConfig()   // large thumbnail + video preview
galleryExtensionConfig()        // image gallery focus
audioExtensionConfig()          // square thumbnail + audio downloads
```

### Usage

```kotlin
// Using a preset
ExtensionScreenTemplate(
    platform = "spotify",
    config   = audioExtensionConfig("Spotify Downloader"),
    onBack   = { navController.popBackStack() },
)

// Fully custom configuration
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
            ScreenBlock.CustomButton(label = "Open in Browser", onClick = { /* ... */ }),
        )
    ),
    onBack = { navController.popBackStack() },
)
```

## Related Repositories

| Repository | Description |
|---|---|
| [`torikomi`](https://github.com/univzy/torikomi) | ← This repo. Main Android app source code |
| [`torikomi-source`](https://github.com/univzy/torikomi-source) | Extension source code (Kotlin scrapers) |
| [`torikomi-extensions`](https://github.com/univzy/torikomi-extensions) | Extension catalog and release APKs |
