<p align="center">
  <img src="torikomi.png" width="220" alt="Torikomi" />
</p>

<h1 align="center">Torikomi</h1>

<p align="center">
  <b>A universal media downloader for Android, powered by yt-dlp.</b>
  <br/>
  Built with Kotlin and Jetpack Compose (Material 3).
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

Torikomi lets you paste or share any link from 1800+ supported sites (YouTube, TikTok,
Instagram, Twitter/X, SoundCloud, and many more) and download video or audio in a
quality you choose. The download engine is [yt-dlp](https://github.com/yt-dlp/yt-dlp),
bundled via [`io.github.junkfood02.youtubedl-android`](https://github.com/junkfood02/youtubedl-android).

## First launch

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
- JDK **11** or newer
- The bundled Gradle wrapper (no global Gradle install required)

> Note: the APK is large (~70–80 MB) because it bundles a Python runtime and FFmpeg for
> two ABIs (arm64-v8a and armeabi-v7a).

### Commands

#### Windows (PowerShell)

```powershell
# Debug APK
.\gradlew.bat assembleDebug

# Release APK
.\gradlew.bat assembleRelease
```

#### macOS / Linux

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`.

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

If no signing config is provided, the release build falls back to the debug keystore.

## Core Developers

[TobyG74](https://github.com/TobyG74) • [arugaz](https://github.com/arugaz) • [nugraizy](https://github.com/nugraizy)

## License

MIT © Torikomi Developers
