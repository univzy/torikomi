# Rust desktop, exact formats, JSON backup and gradient redesign

Scope authorized by the user: replace Electron on Windows/macOS with Rust, show
all actual source formats without recommended/best presets, replace WebDAV with
manual JSON import/export, and apply visible gradient themes with a modern UI.
User explicitly confirmed that JSON replaces WebDAV rather than supplementing it.

Desktop uses Tauri 2: native Rust commands/storage/process management and the OS
WebView, with the existing local HTML/JS UI redesigned. Android remains Compose
and iOS remains SwiftUI. Preserve the four platform folders and all local work.

1. Implement and test Android exact format selection, JSON backup, and full-screen
   gradients (independent Android worker).
2. Implement and test portable iOS backup code, native file import/export and
   gradients; direct media URLs expose original media only (independent iOS worker).
3. Implement and test Rust/Tauri native storage, yt-dlp inspection/download,
   progress/cancel, file dialogs, migration and backup (independent Rust worker).
4. Redesign desktop around a gradient workspace, inspect-first format browser,
   explicit audio track selection and backup preview; use accessible native controls.
5. Centralize readable gradient stops and new translation strings in the generator.
6. Replace Electron dependencies/build/release jobs with Tauri/Rust packaging.
7. Verify Android tests/lint/APK, Python/catalog checks, Rust tests/build and desktop
   browser/native smoke paths. Inspect screenshots and request independent review.

JSON format remains the validated schema-1 document, containing preferences and
library entries/tombstones. Exclude media, credentials and local paths. Parse and
validate before mutation; merge library revisions and deliberately restore imported
preferences using a new local timestamp. Keep original Electron files intact during
Rust data migration. Existing per-device downloaded files remain local.

Format selection uses exact yt-dlp IDs, preserving codec/FPS/container differences
at the same resolution. No automatic best fallback after an explicit selection.
Video-only formats are labeled and can be combined with an explicitly chosen audio
track. Missing formats are reported rather than silently substituted.

Apple builds and native rendering require macOS/Xcode and run in the release CI.

## Verification and review

- Android: full unit tests, lint and debug APK pass. Added regressions for exact
  source IDs, storyboard exclusion, strict backup types/bounds/merge safety and
  preserving legacy/regional language tags in existing data.
- Rust: 13 active tests pass (three ignored functions are subprocess fixtures
  invoked by the active process tests); Clippy all-targets passes with warnings
  denied. Node catalog tests and Python generation tests pass.
- Browser UI: exact video/audio IDs and MP3 conversion, no automatic selection,
  actual gradient changes, preview cancel/apply, keyboard, JSON workflow, RTL,
  font scaling to 150% and widths 375–1440 pass.
- Native Windows Tauri/WebView2: temporary profile, Rust IPC, theme/language
  persistence across reload and real yt-dlp inspection/download pass. Downloaded
  local MP4 bytes match the source exactly. Direct URLs lacking codec metadata
  initially failed and now have a regression test and explicit original-format
  handling. Native screenshots are under shared/desktop/build/previews/.
- Windows NSIS installer builds through the new platform wrapper; previous
  Electron outputs are preserved under build/previous-electron-packages/.
- Manual connected-code review checked storage migration, validation, import
  transaction boundaries, exact selection and stale UI state. The requested
  independent reviewer could not run because its service usage limit was reached.

Native Android UI/file pickers and Apple compilation/UI remain unverified locally.
The Windows installer is unsigned; macOS/iOS builds and signing require their CI
environment and credentials. Newly authored translations remain drafts.
