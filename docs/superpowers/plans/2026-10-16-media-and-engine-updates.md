# Media Library and Engine Updates Implementation Plan

> **For agentic workers:** Use the independent-domain dispatch workflow, then integrate and verify together. Steps use checkbox syntax for tracking.

**Goal:** Implement the two approved desktop specifications without losing existing downloads or portable data.

**Architecture:** Extend the existing JSON document and Rust services, reuse the thumbnail pipeline, add one shared browser player and History/Favorites views, and distribute independently tested engine updates through the pinned repository.

**Tech Stack:** Rust/Tauri, system WebView, JavaScript/CSS, existing Python packaging scripts and GitHub Actions.

**Specs:** [Media](../specs/2026-10-16-media-library-player-design.md), [Updates](../specs/2026-10-16-engine-auto-update-design.md).

## Global constraints

- User approved both proposals with "oke ubah". Implement continuously in the current shared workspace; preserve all earlier changes and do not publish or replace the installed app.
- Desktop targets: Windows x86_64, macOS x86_64/aarch64; portable schema readers also update on Android/iOS.
- Keep schema-1 imports, local-only media paths, 2 MiB portable backups, 5,000 entries, and original downloaded files.
- No arbitrary file access or update URLs from UI/backup data. Bounded media reads, verified engine downloads, and atomic persistence are mandatory.
- Retain themes, 24 languages, RTL, larger text, keyboard access, and reduced motion. User-selected anti-slop During remains active.

## Review focus

- Attached album artwork must not make an audio entry a video.
- Failed cover processing must not erase a successfully downloaded file or register a failed transfer as completed.
- Schema migration/merge must preserve independent folder memberships and prevent tombstone resurrection.
- Large media seeking and malformed range requests must stay bounded and never expose another file.
- Updates must not replace user-managed paths, overwrite an active executable, downgrade a tool, or activate a partially verified set.

### Task 1: Portable media/favorite records

- [x] Add failing tests for v1 migration, v2 metadata/folders/membership merge, validation and removal behavior.
- [x] Extend `core.rs`, `storage.rs`, Android/Swift document types, and shared fixtures with matching schemas and local mutation methods.
- [x] Run native core tests and available Android/Swift checks; report platform limitations precisely.

Interface: `Entry.metadata: Option<MediaMetadata>`; metadata has `artist`, `album`, `albumArtist`, `trackNumber`, `genre`, `year`, `duration` optional fields. `Document.folders` and `Document.memberships`; membership fields `folderId`, `entryId`, `updatedAt`, `deviceId`, `deletedAt`; built-in folder ID `favorites`. Storage methods `create_folder(name)`, `rename_folder(id,name)`, `remove_folder(id)`, `favorite(id,enabled)`, `folder_add(folder,id)`, `folder_remove(folder,id)`. Each returns `Result<_,String>` and uses existing revision semantics. Extend `preview_file` to accept audio too.

### Task 2: Native media and audio metadata

- [x] Add failing tests for source normalization, container-aware tagging/artwork, audio preview, media types and bounded byte ranges.
- [x] Add `media.rs`, extend `preview.rs`, and add native fixture tests. Root integrates the IPC/custom protocol and download caller.
- [x] Prove real tagged audio/cover generation and local range responses with installed FFmpeg/FFprobe and yt-dlp fixtures.

Interface: `media::source_metadata(raw:&Value)->(String, Option<core::MediaMetadata>)`; `media::audio_arguments(formats:&[Value],id:&str,output:&str)->Vec<String>` appends safe metadata/artwork CLI options; `media::file_info(path:&Path, ffmpeg:&str)->async Result<Value,String>` returns kind/metadata/duration/extension; `media::response(path:&Path, method:&str, range:Option<&str>)->Result<tauri::http::Response<Vec<u8>>,String>`. Native module owns optional playback-copy helper if needed and advertises the exact signature. Previewer accepts kind audio and extracts embedded cover with bounded work.

### Task 3: Verified engine updater and publishing pipeline

- [x] Add failing tests for feed manifests, URL/size/hash/version validation, staging failure, manual paths and activation.
- [x] Add focused `updater.rs`, extend reusable setup helpers if necessary, add local updater state in separate `engine-updates.json`, and native/publishing tests.
- [x] Add scheduled/manual packaging/feed workflow and extend release validation without publishing anything in this session.
- [x] Run unit/offline packaging checks. Root integrates commands/background scheduling and settings UI from the returned interface.

Interface: updater gets data root, current selected paths, bundle/platform; exposes async check/install and serializable status, owns local toggle/timestamps and immutable staged installations. It returns candidate paths for root's atomic LocalState commit, never changes `app.rs` or `storage.rs`. Existing reqwest/process/SHA helpers should be reused. Updater agent owns new compression dependency if needed, `setup.rs`, preparation/update scripts and `.github/workflows/` changes.

### Task 4: UI and application integration

- [x] Extend UI contract tests first for History filters, audio thumbnails, favorites/folders, persistent playback, missing files, unsupported media and updater settings.
- [x] Root integrates Rust IPC, media protocol/CSP, source metadata/download finalization and background updater scheduling.
- [x] Root extends `index.html`, `app.js`, focused player module and styles; retain one shared audio session and existing photo loader.
- [x] Add generated catalog strings, then run UI/native smoke tests, core suites, Clippy, catalogs and release-workflow validation.

### Task 5: Delivery

- [x] Review connected data and trust boundaries; fix identified issues and rerun affected checks.
- [x] Record anti-slop gate evidence, limitations, actual upstream/update-feed status, and native playback results.
- [x] Build current Windows installer, copy artifacts, verify package integrity. Report macOS/Android/iOS checks actually run, and do not claim a feed is live until published.

Evidence: [verification and anti-slop delivery gate](../../verification/2026-10-16-media-engine-updates.md).
