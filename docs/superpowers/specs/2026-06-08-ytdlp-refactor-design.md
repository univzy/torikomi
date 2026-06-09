# Torikomi: Refactor from Extension-based to yt-dlp-based

**Date:** 2026-06-08
**Status:** Design — pending user review

## 1. Summary

Torikomi is currently a multi-platform media downloader whose download logic lives
entirely in **external extension APKs**. The app discovers installed extensions
(`com.torikomi.extension_*`), queries their `scrape` ContentProvider for download
links, fetches a remote catalog (`index.json`) to install/update them, and hands the
resolved direct URL to Android's `DownloadManager`.

This refactor **removes the extension system entirely** and replaces it with an
embedded **yt-dlp** engine (via the `youtubedl-android` library). yt-dlp performs both
metadata extraction and the actual download. The app becomes a single **universal URL
downloader**: paste/share any link → fetch info → pick quality → download.

On entry, the app **requires the user to download yt-dlp first** (a gated setup step),
after which downloads are enabled.

## 2. Goals / Non-goals

**Goals**
- Remove all extension code, models, UI, catalog, and related manifest entries.
- Embed yt-dlp + FFmpeg via `youtubedl-android`.
- First-launch **required gate** that downloads the latest yt-dlp binary before any
  download is allowed. Existing (upgraded) extension-based installs hit this gate too.
- Universal single-URL Home flow with curated quality presets.
- Preserve History, Settings, theme, app self-update, and the WhatsApp Status feature.

**Non-goals**
- No `aria2c` integration (optional future speed-up).
- No backward compatibility with extension APKs; installed extension APKs on the device
  are left untouched (not uninstalled by us), simply no longer used.
- No playlist batch-download UX overhaul beyond single-item handling (playlists may be
  treated as single best-item or flagged; see Open Questions).

## 3. Key decisions (from brainstorming)

| Decision | Choice |
|---|---|
| yt-dlp runtime | `youtubedl-android` (junkfood02 fork). Python bundled (unavoidable); yt-dlp **binary downloaded at runtime**. |
| "Download yt-dlp first" | **Required setup gate** on app entry; blocks Home until success. |
| FFmpeg | **Bundled, always on** (best quality: 1080p/4K merges, MP3 extraction). |
| Scope | **Full replacement** — remove extension system completely. |
| WhatsApp Status | **Kept** as a self-contained bonus feature with an entry point. |
| ABIs | **arm64-v8a + armeabi-v7a** only (drops x86/x86_64 to limit bundled size). |
| Download execution | **Foreground service**, yt-dlp does the download; file published to destination. |
| Quality UX | **Curated presets** + expandable "All formats". |

## 4. Technical constraints (must respect)

- **Android W^X / SELinux (API 29+):** native binaries cannot be executed from the app's
  writable data dir. They must ship in the APK (`nativeLibraryDir`). This is why:
  - yt-dlp (a Python script run by the bundled native Python) **can** be downloaded at runtime.
  - FFmpeg and Python (native ELF) **must** be bundled in the APK.
- `youtubedl-android` `init()`, `getInfo()`, `execute()` are **blocking** → must run on
  `Dispatchers.IO`. `init()` extraction takes a few seconds on first run (show on splash).
- `jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"` are required so
  the native libs are extracted and executable.
- minSdk stays 24 (library supports it).

## 5. Architecture

### 5.1 Engine layer (new) — `univzy.torikomi.engine`

`YtDlpEngine` — singleton held by `Torikomi` (replaces `ExtensionBridgeHandler` +
`ExtensionRepository`):

- `suspend fun init()` — `YoutubeDL.init(ctx)` + `FFmpeg.init(ctx)`; idempotent; tracks
  initialized state. Extracts bundled Python + FFmpeg.
- `suspend fun ensureLatestYtDlp(onProgress): UpdateResult` — `updateYoutubeDL(ctx, STABLE)`;
  on success persists `ytDlpReady = true` and stores version. **This is the gated
  "download yt-dlp" step.**
- `suspend fun version(): String`
- `suspend fun fetchInfo(url: String): MediaInfo` — wraps `getInfo`, maps the library's
  `VideoInfo` → domain `MediaInfo`.
- `suspend fun download(spec: DownloadSpec, onProgress: (Float, Long, String) -> Unit): DownloadResult`
  — builds a `YoutubeDLRequest`, runs `execute` with progress callback, returns produced
  file path.

`EngineState` persistence — via `SettingsRepository` (existing `torikomi_settings`
DataStore): `ytDlpReady: Boolean`, `ytDlpVersion: String`, `updateChannel: String`
(default STABLE).

### 5.2 Domain models — `data/model`

**Remove:** `ExtensionInfo`, `ExtensionSource`, `ExtensionStatus`, `InstalledExtensionInfo`,
`ExtensionMeta`, `ScrapeResult`, `DownloadItem`.

**Add:**
```kotlin
data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String,
    val thumbnail: String,
    val durationSec: Int,
    val isPlaylist: Boolean,
    val formats: List<MediaFormat>,
    val detectedSite: String,   // from yt-dlp "extractor" / host, for display + history
)

enum class MediaKind { VIDEO, AUDIO, MUXED }

data class MediaFormat(
    val formatId: String,
    val kind: MediaKind,
    val ext: String,
    val resolution: String?,    // e.g. "1920x1080" / null for audio
    val heightPx: Int?,         // for preset bucketing
    val abrKbps: Int?,          // audio bitrate
    val filesizeApprox: Long?,
    val note: String,           // human label
)

// What the UI selects; engine compiles this into a yt-dlp format string + post-processing.
data class QualityChoice(
    val label: String,          // "Best", "1080p", "720p", "Audio (MP3)"
    val type: String,           // "video" | "audio" (history downloadType)
    val formatSelector: String, // e.g. "bestvideo[height<=1080]+bestaudio/best"
    val audioExtract: Boolean,  // true → -x --audio-format mp3
)
```

**Keep:** `DownloadHistory` (unchanged). `platform` field is populated from
`MediaInfo.detectedSite`.

### 5.3 Download execution & storage

- **`DownloadService`** (foreground service, new): wraps `YtDlpEngine.download`, posts a
  progress notification, exposes active-download state (via a singleton
  `DownloadController`/StateFlow observed by the UI). Survives app backgrounding.
  - Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`; service `serviceType="dataSync"`.
- yt-dlp output template targets an **app-private dir** (`cacheDir/ytdlp` or
  `filesDir/ytdlp`). FFmpeg merges/extracts there.
- **`DownloadPublisher`** (new helper) moves the produced file to the destination:
  - **Default** → `Downloads/Torikomi/<VIDEO|MUSIC|IMAGES>/<name>` via **MediaStore**
    (API 29+) or direct `File` (legacy storage). Reuses `MEDIA_TYPE_FOLDERS`.
  - **Custom folder** → existing SAF (`DocumentsContract`) copy logic, lifted out of the
    old `DownloadViewModel.copyFileToCustomFolder`.
  - Runs `MediaScannerConnection.scanFile` so files appear in the gallery.
- On success → `HistoryRepository.saveDownload(...)`.
- The old `DownloadManager` enqueue, the polling resolver, and the post-json resolver are
  **removed** — yt-dlp handles redirects/extraction natively.

### 5.4 Navigation & screens — `ui`

`SplashDestination` → `LOADING, ONBOARDING, SETUP, HOME`.

- **SplashScreen** — keep visuals; `SplashViewModel` now: `engine.init()` → read flags →
  route. (LOADING shows extraction step text.)
- **OnboardingScreen** — adapted: Welcome → Notifications (13+) → Storage → Done.
  **Removed:** `INSTALL_PERMISSION` step (no APK installs). Welcome/About copy updated
  ("powered by yt-dlp" instead of "plugin-based extensions").
- **SetupScreen** (new) — required gate. Shows "Download yt-dlp", progress, retry on error,
  success → Home. Keyed on `ytDlpReady` (not `onboarding_done`), so upgraded extension
  users also see it.
- **HomeScreen** — bottom nav reduced to **Downloads · History · Settings**.
  - **Downloads tab** (rewritten): URL field (prefill from share/clipboard) → Fetch →
    `MediaInfo` result card (thumbnail/title/uploader/duration) → quality presets +
    "All formats" expander → active downloads list → share hint → small WhatsApp Status
    shortcut card.
  - History tab, Settings tab unchanged structurally.
- **DownloadScreen / ExtensionScreenTemplate / BlockRenderer / ScreenBlock / PlatformCard**
  — **removed**. The download UI is rebuilt inline in the Downloads tab (and/or a result
  composable).
- **WhatsAppStatusScreen** — kept; reached via Settings entry and/or Home shortcut; nav
  route `whatsapp` retained.
- **Share intent** (`App.kt`): extract any http(s) URL → navigate Home with prefilled URL
  (no platform argument). Remove `download/{platform}` route.

### 5.5 ViewModels — `ui/ViewModels.kt`

- **Remove** `ExtensionViewModel`.
- **Rewrite** `DownloadViewModel` → drives fetch-info + quality selection + delegating to
  `DownloadService`/`DownloadController`; drops resolver/`DownloadManager` code.
- **Add** `SetupViewModel` (drives the gate) and **adapt** `SplashViewModel`
  (engine init instead of catalog preload).
- **Keep** `ThemeViewModel`, `HistoryViewModel`, `SettingsViewModel`,
  `StartupUpdateViewModel`. Update `AppViewModelFactory` wiring (inject `YtDlpEngine`).

### 5.6 Repositories / services

- **Remove** `ExtensionRepository`, `ExtensionBridgeHandler`, DataStore `torikomi_ext_prefs`.
- **Keep** `HistoryRepository`, `SettingsRepository` (add `ytDlpReady`/`ytDlpVersion`/
  `updateChannel` accessors), `UpdateChecker` (app self-update — unrelated to extensions).
- **Migration cleanup:** on first launch of the new version, clear the
  `torikomi_ext_prefs` DataStore. History and folder settings preserved.

### 5.7 `UrlDetectorService`

- Loosen `isValidMediaUrl`/`extractUrl` to accept **any** `http(s)` URL (yt-dlp supports
  1800+ sites). Keep `detectPlatform`/emoji only for display (icon/label), not gating.

### 5.8 Build & manifest

`build.gradle.kts`:
- Add `youtubedl-android` `:library` + `:ffmpeg`.
- `defaultConfig.ndk.abiFilters = ["arm64-v8a", "armeabi-v7a"]`.
- `packaging { jniLibs { useLegacyPackaging = true } }`, plus any required `pickFirsts`/
  excludes per library docs.
- `android:extractNativeLibs="true"` in manifest `<application>`.

`libs.versions.toml`: add `youtubedl-android` version + library aliases.

`AndroidManifest.xml`:
- **Remove:** `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, the
  `com.torikomi.extension.action.DISCOVERY` query, the APK-install `FileProvider`.
- **Add:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`; declare `DownloadService`.
- **Keep:** `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, storage perms,
  share intent-filter, launcher.

## 6. Removed vs kept (file-level)

**Removed**
- `service/ExtensionBridgeHandler.kt`
- `data/repository/ExtensionRepository.kt`
- `ui/template/ExtensionScreenTemplate.kt`, `ui/template/BlockRenderer.kt`, `ui/template/ScreenBlock.kt`
- `ui/components/PlatformCard.kt`
- `ui/screens/DownloadScreen.kt` (replaced by inline Downloads UI)
- Extension models in `data/model/Models.kt` + extension parsing in `data/model/JsonMappers.kt`
- Catalog/`index.json` usage

**Kept / adapted**
- `data/repository/HistoryRepository.kt`, `ui/screens/HistoryScreen.kt`
- `data/repository/SettingsRepository.kt` (+yt-dlp keys), `ui/screens/SettingsScreen.kt` (+yt-dlp section)
- `service/UpdateChecker.kt`, `StartupUpdateViewModel`
- `ui/screens/OnboardingScreen.kt` (trim install-perm step)
- `ui/screens/SplashScreen.kt`, `ui/screens/WhatsAppStatusScreen.kt`
- `util/UrlDetectorService.kt` (loosened)
- `ui/theme/*`, `MainActivity.kt`, `Torikomi.kt` (wire `YtDlpEngine`)

## 7. Testing

Pure-Kotlin unit tests (no device needed):
- `VideoInfo → MediaInfo` mapping.
- Quality-preset derivation from `formats` and `QualityChoice → yt-dlp format selector` string.
- `UrlDetectorService` accepts arbitrary URLs / extracts from shared text.
- `DownloadPublisher` destination path construction (default + media-type folders).

On-device manual verification (needs native runtime + network):
- First-launch gate downloads yt-dlp; Home blocked until ready.
- Fetch info + download for a few sites (YouTube video+audio merge, audio→MP3, an image post).
- Custom SAF folder + default folder destinations; history records + file deletion.

## 8. Risks

- **APK size** grows substantially (bundled Python + FFmpeg, two ABIs). Mitigated by
  arm-only ABIs.
- **`youtubedl-android` packaging quirks** (jniLibs/legacy packaging, dexing) — must follow
  library setup precisely; first integration spike validates build runs on device.
- **Foreground-service policy** on Android 14+ requires correct `serviceType` + runtime
  notification permission already handled.
- **Storage on API 29–30** (legacy vs MediaStore) — `DownloadPublisher` must branch
  correctly; covered by tests + manual checks.

## 9. Open questions (default answers chosen; flag if you disagree)

- **Playlists:** default = treat a playlist URL as its single best entry / first item, with
  a note. Full batch-download UX is out of scope for this refactor.
- **Update channel:** default Stable only; Nightly toggle optional in Settings.
- **WhatsApp entry point:** default = a card on the Downloads tab + a Settings row.
