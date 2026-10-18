<p align="center">
  <img src="torikomi.png" width="220" alt="Torikomi" />
</p>

<h1 align="center">Torikomi</h1>

<p align="center">
  <b>A media library and downloader for Android, Windows, macOS, and iOS.</b>
  <br/>
  Compose on Android, Rust/Tauri on desktop, SwiftUI on iOS. Transfer your library with JSON backups.
</p>

<p align="center">
  <img alt="Version"      src="https://img.shields.io/badge/Version-2.0.0-blue" />
  <img alt="Min SDK"      src="https://img.shields.io/badge/Min%20SDK-24-3DDC84?logo=android&logoColor=white" />
  <img alt="Target SDK"   src="https://img.shields.io/badge/Target%20SDK-35-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin"       src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose"      src="https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="License"      src="https://img.shields.io/badge/License-MIT-blue.svg" />
</p>

---

## What it is

| Application | Source | Downloads |
|---|---|---|
| Android 7+ | `android/` | Embedded yt-dlp + FFmpeg; gallery-dl installed on first use; local player and audio folders |
| Windows 10+ | `windows/` | gallery-dl + yt-dlp + FFmpeg downloaded during setup; Windows 11 22H2+ acrylic |
| macOS | `macos/` | Engines downloaded during setup; native vibrancy; Intel and Apple Silicon DMGs |
| iOS / iPadOS 17+ | `ios/` | Direct HTTPS media files; save site links for Android/desktop |

```text
android/        Android source and Gradle module
windows/        Windows commands, installer configuration and dist/
macos/          macOS commands, DMG configuration and dist/
ios/            SwiftUI application and XcodeGen project
shared/desktop/ Shared Rust/Tauri backend, local UI, tests and dependency lockfiles
sync/           Shared schema-1 migration and schema-2 JSON backup fixtures
```

The Gradle wrapper, SDK configuration and signing files remain at the repository
root. Open that root as the Android Studio project; its application module is
`:android`. Desktop source is shared without duplicating it between OS folders.

All four applications support system/light/dark themes, four neutral and 20 gradient color presets,
notification preferences, font family, text size and comfortable/compact layouts.
Desktop uses a sidebar, Android uses bottom navigation or a tablet rail, and iOS
uses tabs or an iPad sidebar. Glass is optional and respects reduced transparency.

### JSON backup and restore

In **Settings → Backup & library** (desktop/iOS: Backup in Settings), export a JSON
file and import it on another device. No server or account is required. Import
previews the backup before applying it, restores its preferences, and merges
library links and removals. Android also includes download-history metadata.
Media files, local paths, login cookies and account credentials are excluded.
Keep your backup private because it contains your saved URLs and preferences.

All four clients support 24 languages. See [the JSON format and platform design](docs/platforms.md).

The remaining original feature list below describes the Android application.

Torikomi lets you paste or share any link from 1800+ supported sites (YouTube, TikTok,
Instagram, Twitter/X, SoundCloud, and many more) and download video or audio in a
quality you choose. The download engine is [yt-dlp](https://github.com/yt-dlp/yt-dlp),
bundled via [`io.github.junkfood02.youtubedl-android`](https://github.com/junkfood02/youtubedl-android).

## Desktop first launch

The Windows installer and native macOS DMGs contain the application only.
The first-launch welcome screen lets you choose a language, then download gallery-dl,
yt-dlp, FFmpeg and FFprobe or select installed executables manually. No separate Python
or Homebrew installation is needed by users. Automatic setup shows download progress
and supports cancellation. It verifies pinned archive and executable SHA256 hashes,
sizes and tool versions before activating the complete set. Failed or cancelled setup
preserves existing paths. Successful automatic setup saves
completion with the verified paths, even if you close before the final button.
Setup can be reopened in Settings.

Paste a media link and choose **Show formats**. Torikomi detects supported photos,
albums and video/audio automatically. Select some or all photos to save each as a
separate library item. Mixed posts can show both photos and video-format choices;
video and audio still retain explicit format and audio-track selections.
Delegated collections require an individual post/album URL. Authenticated and
unsupported sites may need additional support; not every site exposes its media publicly.

Engine paths and first-launch state are local to this device and excluded from JSON
backups. See [desktop build details and engine versions](docs/platforms.md).

Desktop History shows local photo, video and audio thumbnails with media filters.
Audio downloads retain available title, artist, album and container-supported artwork.
Favorite audio can be organized in named folders, while the shared player continues
across pages with seek, volume, shuffle and repeat. Playing audio opens a dedicated
Now playing page with artwork and a play queue; a compact player remains on other
pages. Music rows show artwork, title, artist and duration on one line, with the
current track highlighted. Volume has 1% steps, mute and a saved listening level.
Player volume and mute are saved locally in the WebView and excluded from JSON
library backups. Playback history is not recorded.
History and favorite-track actions use labeled icons with tooltips. Photos
and video open inside the application; the system-app action remains available.

Settings includes automatic engine updates and a manual check. Only managed tools
are replaced after package verification, and activation waits for native work to
finish. The separate engine-feed workflow must be deployed and publish its first
package before remote updates are available; this workspace has not published one.

## Android first launch

1. Install the APK (Android 7.0 / API 24 or newer).
2. Walk through the **onboarding wizard** — grant notification permission (and storage
   permission on Android 9 and below) and choose a download folder.
3. On the **Setup screen**, tap **Download yt-dlp**. The app fetches the latest yt-dlp
   binary from GitHub. This is a one-time required step; downloads are blocked until it
   completes. You can update yt-dlp later from **Settings → yt-dlp Engine**.
4. The Home screen unlocks. Paste or share a URL, fetch info, pick a quality, download.

> The bundled Python runtime and FFmpeg are included in the APK; only the yt-dlp
> script itself is fetched at runtime (a small download).

## Features

- **Universal URL downloading** — paste any link or share from any app; yt-dlp resolves
  it.
- **Quality presets with size estimate** — Best (video + audio merged), resolution-stepped
  options (e.g. 1080p, 720p, …), and Audio (MP3 extracted), each showing an estimated file
  size. FFmpeg handles merging and MP3 extraction.
- **Download Now or Later** — start a download immediately, or save it to a queue to grab
  later.
- **Downloads hub** — a top-bar icon (with a count badge) opens a *Now* tab (active
  progress) and a *Later* tab (the saved queue, with download / download-all / remove).
- **Foreground-service downloads** — progress shown in a persistent notification;
  downloads survive app backgrounding.
- **Flexible storage** — saves to `Downloads/Torikomi/` by default (organised into
  `VIDEO/`, `MUSIC/`, `IMAGES/` sub-folders), or to a custom folder you pick via the
  Storage Access Framework. In a custom SAF folder, files are written with a type prefix
  (e.g. `VIDEO_clip.mp4`, `MUSIC_song.mp3`).
- **Download history** — type/platform filters, share, and delete-with-file.
- **Statistics** — total downloads, total data downloaded, a breakdown by type, downloads
  in the last 7 days, and top platforms.
- **Appearance** — Theme (System / Light / Dark) and an in-app **language switcher** with
  **15 languages** (English, Bahasa Indonesia, Español, Português, Français, Deutsch,
  Italiano, Русский, Türkçe, Tiếng Việt, العربية, हिन्दी, 中文, 日本語, 한국어), applied via a
  per-app locale that works on all supported Android versions.
- **WhatsApp Status saver** — a dedicated navbar tab to browse and save local WhatsApp
  statuses.
- **In-app update check** — checks GitHub Releases on launch and surfaces a notification
  when a newer version is available; update yt-dlp itself from **Settings → yt-dlp Engine**.

## Tech stack

| Component | Library |
|---|---|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + StateFlow/Flow |
| Persistence | DataStore Preferences |
| Networking | OkHttp |
| Image loading | Coil |
| Download engine | `io.github.junkfood02.youtubedl-android` (`:library` + `:ffmpeg`) |

## Build

### Requirements

- Android SDK (compileSdk **35**, minSdk **24**)
- JDK **21** (matching the configured Gradle daemon toolchain)
- The bundled Gradle wrapper (no global Gradle install required)

> Note: the APK is large (~70–80 MB) because it bundles a Python runtime and FFmpeg for
> two ABIs (arm64-v8a and armeabi-v7a).

### Commands

#### Windows (PowerShell)

```powershell
# Debug APK
.\gradlew.bat :android:assembleDebug

# Release APK
.\gradlew.bat :android:assembleRelease
```

#### macOS / Linux

```bash
./gradlew :android:assembleDebug
./gradlew :android:assembleRelease
```

Output: `android/build/outputs/apk/{debug,release}/android-{debug,release}.apk`.

### Signing

Release signing is read from `keystore.properties` (local) or environment variables (CI):

`keystore.properties` (local, **not committed**):

```properties
storeFile=../keys/torikomi-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

| Env variable             | Maps to                       |
|--------------------------|-------------------------------|
| `TORIKOMI_STOREFILE`     | `signingConfig.storeFile`     |
| `TORIKOMI_STOREPASSWORD` | `signingConfig.storePassword` |
| `TORIKOMI_KEYALIAS`      | `signingConfig.keyAlias`      |
| `TORIKOMI_KEYPASSWORD`   | `signingConfig.keyPassword`   |

Without signing configuration, `assembleRelease` produces an unsigned APK. Use
`assembleDebug` for a locally installable debug build.

### Windows and macOS

Requires Node.js 24+, Rust stable, and the platform Tauri prerequisites (MSVC C++
build tools and WebView2 on Windows; Xcode command-line tools on macOS). The desktop
runtime is Rust with the system WebView; Node.js is used only for development tools.
Install yt-dlp and FFmpeg separately, then
select their executables in Settings (or put them on PATH). The app invokes them
without a shell. Downloads continue while the desktop app is open.

```sh
# Windows, from the repository root
npm --prefix windows run setup
npm --prefix windows test
npm --prefix windows start
npm --prefix windows run dist

# macOS, from the repository root
npm --prefix macos run setup
npm --prefix macos test
npm --prefix macos start
npm --prefix macos run dist
```

Installers are written to `windows/dist/` or `macos/dist/`. Each platform's `setup`
command installs the dependencies locked in `shared/desktop/package-lock.json`.
On Windows, select `yt-dlp.exe` and `ffmpeg.exe`, not a shell script or shortcut.
The format browser keeps all source format IDs, including different codecs/FPS at
the same resolution. Select a format explicitly. A video-only format may be combined
with an explicitly chosen audio track; audio formats can be converted with FFmpeg.
Existing Electron data is migrated on first launch without deleting the old files.

### iOS and iPadOS

Requires macOS, Xcode with the iOS 17+ SDK, and XcodeGen. No third-party Swift
packages are required. The generated Xcode project is intentionally git-ignored.

```sh
swift test --package-path ios
cd ios
xcodegen generate
open Torikomi.xcodeproj
```

Choose your Apple development team to run on a device. iOS downloads direct media
links with URLSession while the app is active. HTML site pages are rejected with
an explanation; export those saved links as JSON to use yt-dlp on another platform.
Downloaded files appear in Library's Share action and Files → Torikomi.

### Four-platform releases

`.github/workflows/release.yml` builds Android, Windows, macOS and iOS separately.
`workflow_dispatch` builds artifacts for inspection; a `v*` tag publishes a GitHub
release only after all four jobs succeed. Unit tests, Android lint, desktop UI
smoke checks and Swift core tests run before packaging.

Android tag builds require the keystore secrets listed above. Manual builds can
produce a debug APK when no release key is configured.

Desktop signing is optional. The macOS workflow uses base64 `MAC_CERTIFICATE`,
`MAC_CERTIFICATE_PASSWORD` and `MAC_SIGNING_IDENTITY`. Without a certificate, macOS
builds use ad-hoc signing and skip notarization. Certificate-signed builds require
`APPLE_ID`, `APPLE_APP_SPECIFIC_PASSWORD` and `APPLE_TEAM_ID` for notarization.
Windows CI imports a base64 PFX from `WINDOWS_CERTIFICATE` with
`WINDOWS_CERTIFICATE_PASSWORD` when
provided. Local builds can use `certificateThumbprint` or a signing command in
`windows/tauri.conf.json`. Unsigned builds may show OS security prompts.

For an installable iOS IPA, configure `IOS_CERTIFICATE_BASE64`,
`IOS_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64` and `APPLE_TEAM_ID`.
The provisioning profile must match `univzy.torikomi.ios`. Repository variable
`IOS_EXPORT_METHOD` defaults to `release-testing` (registered devices); it may be
set to `app-store-connect` for an App Store export. Exporting does not upload to
Apple. Without signing secrets, CI publishes an **unsigned `.xcarchive.zip`** for
opening and signing in Xcode, rather than claiming to provide an installable IPA.

### Verification

```sh
python scripts/generate_locales.py --check
python -m unittest discover -s scripts -p test_generate_locales.py
./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
npm --prefix windows test
npm --prefix windows run test:ui
```

Before UI smoke tests, run `npm --prefix shared/desktop run setup:browser` once.
Desktop UI smoke tests run in Playwright Chromium with the same IPC contract;
Rust tests exercise actual storage, backup validation, process output and cancellation.
The optional `shared/desktop/test/native-smoke.cjs` checks a debug Windows Tauri
window via WebView2 CDP and actual local yt-dlp media downloads, using a temporary
`TORIKOMI_DATA_DIR`. Production builds ignore that debug-only data-directory override.
Screenshots are written to `shared/desktop/build/previews/`. The shared
`sync/fixtures/v1.json` is exercised by Kotlin, Rust and Swift tests.

### Themes and translations

All four apps share four neutral themes, 20 color presets, a follow-device
switch and a preview that can be applied or dismissed. The 24 language catalogs
include Arabic/Persian RTL and Simplified/Traditional Chinese. Theme and language
preferences transfer through JSON import/export. Gradients color the application
background while cards preserve text contrast.

Edit `localization/locales/*.json`, then run `python scripts/generate_locales.py`
to regenerate Android, desktop and iOS resources. No translation API is required.
See [localization/README.md](localization/README.md) for adding a language and
validating placeholders. New translations should receive native-speaker review.

## Core Developers

[TobyG74](https://github.com/TobyG74) • [arugaz](https://github.com/arugaz) • [nugraizy](https://github.com/nugraizy)

## License

MIT © Torikomi Developers
