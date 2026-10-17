# Platform applications

Android lives in `android/` (Jetpack Compose and embedded yt-dlp/FFmpeg). Windows
and macOS have their commands and installer configuration in `windows/` and
`macos/`; the shared Rust/Tauri 2 application lives in `shared/desktop/src-tauri/`.
The local HTML/CSS/JS UI uses the system WebView and has no Node/Electron runtime.
Installers are copied into each platform's `dist/`. iOS lives in `ios/` (SwiftUI,
URLSession and native Files import/export). Android keeps its existing WhatsApp,
converter, queue and statistics features.

All clients share 24 languages, four neutral themes and 20 gradient color themes,
system/light/dark appearance, font and layout preferences. Gradient backgrounds
remain visible behind readable content cards. Desktop Acrylic/vibrancy is optional;
glass mode exposes the native desktop backdrop through a light theme tint while
keeping content surfaces readable. Reduced-transparency preferences restore opaque
CSS surfaces.

## Download format selection

Desktop uses one media-link form. Photo/album detection and fallback to video/audio
inspection happen automatically when Show formats is pressed. Mixed posts can show
both result panels; video errors do not discard usable photos. There is no initial
Video/Photos/Albums mode switch or engine-explanation paragraph in the link form.

Android and desktop display the actual formats returned by yt-dlp, preserving
format IDs, codecs, frame rates, resolution, container and known file size.
There is no recommended/best preset or automatic fallback after explicit selection.
Video-only sources are labeled. Desktop can mux an explicitly selected audio
track, or convert a selected audio format through FFmpeg. Android opens multiple
URLs sequentially so a format can be chosen for each source.

Desktop runs the selected standalone gallery-dl/yt-dlp/FFmpeg executables directly, without
constructing shell commands. iOS downloads the original file from direct HTTPS
media URLs; it cannot enumerate formats from yt-dlp site pages. Such links can be
exported to Android/desktop with the library. There is no remote extraction server.

## JSON backup v2

Export a `.json` file, transfer it with your preferred file service, then import it
on another device. WebDAV has been replaced by this manual flow. Backups contain
preferences and library metadata, including removals. Android also exports local
download-history metadata. Media files, device paths, cookies and credentials are
excluded. A backup contains private URLs, so keep it somewhere appropriate.

```json
{
  "schemaVersion": 2,
  "settings": {
    "updatedAt": 0, "deviceId": "installation-uuid",
    "value": {
      "themeMode": "system", "themeColor": "violet", "fontScale": 1.0,
      "fontFamily": "system", "layout": "comfortable", "language": "system",
      "notifications": true, "glass": false
    }
  },
  "entries": [{
    "id": "uuid", "url": "https://example.org/clip.mp4", "title": "Clip",
    "kind": "video", "createdAt": 0, "updatedAt": 0,
    "deviceId": "installation-uuid", "deletedAt": 0
  }],
  "folders": [],
  "memberships": []
}
```

All times are integer Unix milliseconds, bounded to JavaScript's safe integer
range. Backups are limited to 2 MiB / 5,000 library entries including tombstones.
Parse and validate before changing local state. An import preview requires explicit
application. Merge each entry by greatest `(updatedAt, deviceId)`; a nonzero
`deletedAt` keeps removals from reappearing. Imported preferences are deliberately
restored with a new local revision. Atomic writes preserve the previous local
document if validation or persistence fails. Local media files are retained.

Version-1 backups, including `sync/fixtures/v1.json`, are migrated on import.
Version 2 adds optional audio `metadata` (artist, album, albumArtist, trackNumber,
genre, year, duration), favorite folders and independently versioned memberships.
The reserved `favorites` membership represents all favorite audio. Folder names
are limited to 80 characters; backups allow 100 folders and 20,000 memberships,
including deletion records. Android and iOS preserve these fields on merge/export.
Older app versions that only understand v1 cannot import v2 exports.
No server settings are required. Rust migrates the previous
Electron `torikomi-desktop/library.json` into its Tauri application data directory
only when new data is absent, preserving the original file. Previously saved
password files are never exported or copied into the Rust state.

## Development and verification

Desktop photo selection includes lazily loaded thumbnails. Rust fetches only URLs
from the inspected album, sends its Referer, validates and resizes supported images
to 320px PNG previews, and limits concurrent work and memory. The WebView receives
only thumbnail data URLs; preview images are kept in a bounded memory cache.
JPEG, PNG, WebP, GIF, BMP and TIFF previews are supported. Unsupported formats,
large files and unavailable CDN images show a retryable placeholder; original photo
selection and downloading remain available. Captions occupy at most two visible lines.

Desktop Library thumbnails use downloaded local files, so they remain available
offline. Photos use the same bounded decoder; video previews extract one scaled
frame with the configured FFmpeg and remove the temporary frame afterward. Only
active image/video/audio entry IDs with a mapped local file are accepted. Audio
artwork comes from embedded covers or a retained local thumbnail. Imported entries
without files show an unavailable placeholder until the media is downloaded locally.

Desktop installers contain the application and a pinned download manifest, without
engine executables. Separate release assets provide gallery-dl 1.32.16, yt-dlp 2026.08.19,
FFmpeg and FFprobe. Windows/Intel Mac FFmpeg is pinned to 6.1.1 for this build;
Apple Silicon builds 9.0.2 from verified source with static LAME and Opus.
These initial download versions remain pinned. A newer installed FFmpeg
can be selected manually. The packaging script includes license texts, source links,
build notices and executable hashes in the embedded bootstrap manifest and separate notices asset.

The welcome screen appears until engine setup is finished. Automatic setup runs
verified HTTPS downloads with progress and cancellation; manual setup accepts native executable files,
checks their versions, and preserves previous configuration on failure. Setup state
and paths never appear in portable backups. A changed remote album may reorder its
photos between inspection and downloading; delegated collections are rejected so
the selected range cannot silently restart for multiple child extractors.

Use Rust stable and Tauri's native build prerequisites alongside Node.js 24+ for
desktop development. `npm --prefix windows run setup` or the macOS equivalent
installs the development tools. `npm --prefix windows test` runs Rust core/process
and JavaScript catalog tests; `test:ui` runs the UI contract in Chromium after
`npm --prefix shared/desktop run setup:browser`.

Preparing separate engine assets additionally needs native 64-bit Python 3.13.
`scripts/prepare_desktop_engines.py` downloads pinned HTTPS artifacts with
SHA256 verification and builds a self-contained gallery-dl executable using a hashed
PyInstaller environment. On Apple Silicon, clang, make and pkg-config build static
FFmpeg/audio libraries; Homebrew is used only for developer build prerequisites.
Run `python scripts/pack_desktop_engines.py --bundle shared/desktop/src-tauri/resources/engines/<platform>-<arch> --output <platform>/dist/engines --tag v2.0.0`
from the repository root before building a release. This pins the assets for the current
native target; the CI performs these steps automatically. Publish all generated engine
assets alongside the application release before distributing its installer. The local
Windows manifest currently targets release `v2.0.0`; unpublished assets produce an
actionable retry/manual-setup error. The OS `dist` command builds only the application.
The build cache stays in ignored `build/`; generated engine binaries are ignored too.
Run `python scripts/test_gallery_engine.py --gallery-dl <prepared executable>` for
the offline mixed-album CLI integration check. Never regenerate engine hashes after
installation; packaging signs native tools before creating the manifest.

## Desktop playback and engine updates

History filters video, photo and audio and searches titles, artists and albums.
Favorite audio can be organized in named folders without moving the downloaded
files. Removing a folder keeps the global favorites; removing a History entry also
removes its favorite references while keeping the file. Folder/membership deletion
records prevent older backups from bringing removed records back.

Selected audio downloads retain real source metadata and existing embedded tags.
MP3, M4A, FLAC and Opus/OGG receive embedded artwork when their container and
source support it. WAV and raw AAC keep local artwork separately. Missing tags are
left empty; uploader names and upload dates are not invented as artist/release data.
If only postprocessing fails after a verified completed audio transfer, History keeps
the usable file and shows a warning. Failed or cancelled transfers are not registered.

Audio playback persists across pages with a seek bar, volume, shuffle, repeat and
Now playing artwork. Video and photos open in a separate viewer. Local media URLs
are short-lived opaque handles to active History entries, with bounded reads and
range support for seeking. Full photo responses are capped at 32 MiB. Unsupported
media can be opened in the system app; unsupported audio can explicitly generate
a bounded private MP3 playback copy, leaving the original untouched. Old audio
tags are read and persisted when the file is opened.

Automatic engine updates default on and check at most every 24 hours after setup
or when selected paths change. Settings shows versions, ownership, the toggle and
a manual check button. Only Torikomi-managed paths are updated. Updates use the
fixed `univzy/torikomi` release feed, HTTPS assets, manifest size/hash checks,
version/OS compatibility checks and immutable staging. A verified set activates only
when native work is idle. Failures preserve selected tools. Settings and update state
are local and excluded from backups. Older immutable sets are retained to avoid
deleting an executable another process or manually selected configuration may use;
they can consume additional disk space over time.

`.github/workflows/engine-updates.yml` resolves stable upstream versions, builds
native packages and runs offline compatibility tests before publishing a separate
engine release. This session did not deploy that workflow or publish its first feed.
Until a feed exists, checks report that no package is published and keep installed
engines. Application installers remain on the ordinary four-platform release workflow.

Windows packages NSIS; macOS packages separate native Intel and Apple Silicon DMGs.
Release CI runs catalog checks, Android tests/lint,
Rust tests, browser UI tests and Swift core tests before packaging the four apps.
Apple builds require macOS/Xcode. See [translation generation](../localization/README.md)
for adding languages or changing theme tokens.

## Mobile media library

Android and iOS provide local audio/video playback, photo viewing, artwork/thumbnails,
media-type filters, audio favorites with named folders, highlighted play queues,
seek/volume/mute/shuffle/repeat and schema2 JSON import/export. Playback history is
not stored. Imported library entries retain folder references; media files and local
paths remain on the original device until downloaded again.

Android uses its existing embedded Python/FFmpeg runtime for yt-dlp. Gallery photos
use gallery-dl 1.32.16 and pinned pure-Python dependencies downloaded into private
application storage on first use or through Settings. Wheel sizes and hashes are
verified before extraction. Android's gallery choice downloads the complete album,
up to 100 images, with a 50 MiB per-image transfer limit. Its gallery package lock is
updated with application releases; it does not use the desktop engine update feed.
These galleries still depend on the source
site allowing access; this change does not bypass site restrictions.

iOS follows the selected server-free architecture. It downloads original HTTPS
audio/photo/video files below 2 GiB, reads available embedded metadata and uses
AVFoundation for local playback. It does not execute desktop command-line engines
or extract site pages. The player offers separate application and native system
volume controls. Setup is a one-time introduction, not an engine-install prompt.
