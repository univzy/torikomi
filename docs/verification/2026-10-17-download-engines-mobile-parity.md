# Engine downloads and mobile media features

Desktop installers now contain the application only. The first-launch setup offers
engine downloads or existing executable selection. Download progress and cancellation
are visible, and successful setup persists across restarts. Existing configured paths
continue working. iOS follows the user's direct-HTTPS, server-free architecture.

## Desktop verification

- Native Rust: 77 tests passed, 9 existing environment/real-engine fixtures ignored.
  Fresh setup without a bundle, previous-path preservation, cancellation, truncated
  transfers, gzip/hash/size validation, all executable probes and HTTP404 were covered.
- Rust formatting and all-target Clippy with warnings denied passed.
- Both browser UI suites passed: failure/retry/cancel/progress, media previews,
  favorites, actual playback, volume/mute, queue, RTL, large text and narrow widths.
- Isolated native Windows debug WebView2 passed real MP3/H264/photo playback,
  thumbnail generation, favorite persistence, schema migration, volume, Acrylic and
  200% text. The user's installed app and library were not replaced.
- Engine packaging tests: 3 passed, including the manifest path when invoked from
  a platform directory. All four actual Windows archives were independently checked
  against compressed and expanded sizes/SHA256 in the embedded manifest.
- Translation/theme catalogs: 24 languages and 27 generated resources checked.
  JavaScript catalog tests passed. Python suite: 28 tests, 2 existing optional skips;
  packaging regressions separately passed 3 tests.
- Release workflow parses successfully: Android, Windows, macOS Intel/Apple Silicon,
  and iOS builds; engine gzip/notices assets upload separately from application packages.

## Windows files

`windows/dist/Torikomi_2.0.0_x64-setup.exe`: **4,110,155 bytes**, down from
80,808,476 bytes. NSIS release build and `7z t` passed. Installer listing contains
the application executable and installer plugins, without engine executables.

SHA256: `8d94fe640428c087ae4e9f5b4c4627c594e092249fa0b216ebfaa23dd08c35ad`.
The installer remains unsigned; the existing Indonesian NSIS translation warning remains.

Separate files under `windows/dist/engines/`:

| Asset | Bytes |
|---|---:|
| `torikomi-engine-windows-x86_64-ffmpeg-dc5bde9a9570.gz` | 29,581,301 |
| `torikomi-engine-windows-x86_64-ffprobe-a5ce82072dcd.gz` | 29,521,638 |
| `torikomi-engine-windows-x86_64-gallery-dl-2eb4738ba4de.gz` | 15,577,436 |
| `torikomi-engine-windows-x86_64-yt-dlp-9b5dfd6449bf.gz` | 17,619,838 |
| `torikomi-engine-windows-x86_64-notices-851aa78fd806.json` | 574,548 |

Full compressed/expanded hashes and pinned URLs are in
`shared/desktop/src-tauri/bootstrap-engines.json`. These local files have **not** been
published to the `v2.0.0` GitHub release. Network setup cannot succeed until the exact
assets are available there. The updated tag release workflow publishes matching
assets and application packages together. No external release was modified this session.

## Mobile verification

Android adds native media viewing/playback, exact content-URI history, audio favorites
and folder memberships, compact actions, queue/seek/shuffle/repeat/volume/mute, direct
photos and verified first-use gallery-dl installation.

- Android Gradle `testDebugUnitTest`, `lintDebug` and `assembleDebug` passed.
  47 JVM tests: zero failures/errors/skips. Lint: zero errors, 108 warnings including
  existing deprecations/RTL/style checks.
- Two Python runtime checks passed: actual gallery-dl 1.32.16 wheel integrity/import,
  TikTok photo extractor mapping and rejection of private DNS addresses. They do not
  execute the Android Python binary or request the live TikTok post.
- APK: `android/build/outputs/apk/debug/android-debug.apk`, 82,520,820 bytes.
  SHA256: `f0c615868d6675e7f18ac35b1588871958ac9825242b704cc5eed030fa52845b`.
  This is a debug APK, not a production-signed release.
- Android gallery downloads the complete album (maximum 100 photos, 50 MiB each);
  the gallery package lock is updated with app releases. Live TikTok extraction,
  hardware playback/Python ABI and on-device FFmpeg tagging were not exercised.
- Independent review fixes preserve imported audio IDs/folder references, avoid
  100-entry history eviction, use DocumentsContract for document deletion, keep
  failed deletions visible in History, label folder checkboxes for TalkBack and
  prevent async audio preparation from starting after the app enters the background.

iOS adds adaptive native navigation, media thumbnails/filters, photo/video/audio
playback, favorite folders and membership controls, actual shuffled queue order,
application/system volume and schema2 JSON preservation. Downloads reject unsupported
MIME types and HTTPS downgrade/credentials and stop at 2 GiB. Removing an entry during
download cannot recreate it. One-time introduction persists using AppStorage.

Thirteen Swift files passed syntax parsing. Portable core tests cover folder mutations,
tombstones, queue bounds, shuffle and deleted download targets; they were **not executed**
because Swift/Xcode are unavailable on this Windows host. Native iOS/macOS compilation,
signing and hardware playback remain macOS CI/device checks. Android device playback
and its packaged Python ABI likewise require a device/emulator.

## UI delivery gate

UI UX Pro Max and anti-slop During were applied. The existing Torikomi gradient,
type choices and media-first direction remain; desktop ENERGY2 / RHYTHM1 / MOTION1.

- Hard Gate: desktop progress/cancel, keyboard controls, readable themes, media
  actions and responsive layouts pass browser/native checks. Mobile uses platform
  controls and labeled 44/48-point targets; native device accessibility remains untested.
- Purpose Gate: setup explains the required download and offers manual setup.
  Media actions, folder membership and player controls serve the requested workflows.
- Liveliness: user artwork, current-track accents and saved theme choices carry the
  interface. No extra decorative assets or animation dependency were introduced.
- Craftsmanship: failure/cancellation preserves data; portable folder references
  remain separate from local paths. Independent review fixes prevent deleted-link
  resurrection and ensure the visible queue follows actual shuffled playback.
