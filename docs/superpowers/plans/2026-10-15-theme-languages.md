# Theme selector and language catalogs

User reference: Discord Appearance screenshot; implement consistently across the
four platform folders required by root AGENTS.md. Preserve local work and the
schema-1 WebDAV document.

- [x] Share four neutral and 20 color presets with real light/dark surface colors.
- [x] Replace theme dropdowns with accessible swatches, a follow-device switch and
  a preview whose cancellation leaves saved preferences intact.
- [x] Fill all 318 canonical messages in 24 languages; preserve original translations.
- [x] Generate Android resources/Kotlin metadata, Electron and Swift package catalogs
  using a standard-library Python script; validate keys, formatting and contrast.
- [x] Expand all language selectors and add desktop/iOS locale aliases and RTL.
- [x] Check catalog freshness in release CI before the four platform builds.
- [x] Run Android tests/lint/build and desktop catalog/protocol/UI tests.
- [x] Independent code review of appearance, localization and preference bindings.

Local checks: 30 Android tests, 8 Node tests, 4 Python tests; Electron UI smoke
covers preview apply/cancel, keyboard, persistence, RTL, glass and widths 375–1440.
Android lint has no errors and 92 warnings. Swift tests and native Apple UI/builds
require macOS/Xcode; the updated release workflow runs those checks there.
Translation drafts still need native-speaker review. Older clients must upgrade
before syncing themes they do not yet recognize.
