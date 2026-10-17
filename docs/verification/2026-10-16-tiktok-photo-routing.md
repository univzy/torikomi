# TikTok photo routing and gallery diagnostics

The installer at the shared output path has since been replaced by the
[player-page and setup build](2026-10-16-player-page-and-setup.md). The hash below
records this earlier build.

The user reported that TikTok photos downloaded in Torikomi's old separate photo
mode, but the unified input now displayed Unsupported URL and No supported photos.
The requested input remains unified. This change restores the gallery-only route
for canonical TikTok photo posts and fixes two independently verified defects.

## Evidence and changes

- A single live metadata-only attempt with the bundled gallery-dl 1.32.16 returned
  process status 0, stdout `[]`, and stderr containing `403 Forbidden`. The old
  process API discarded successful-process stderr, so the gallery parser reported
  an empty album and the UI attempted an unsupported video extraction afterward.
  No further live TikTok attempts were made after that access rejection.
- [Upstream TikTok extraction](https://github.com/mikf/gallery-dl/blob/v1.32.16/gallery_dl/extractor/tiktok.py)
  identifies background music as `type=audio`. Its extension can be MP4, which
  Torikomi previously classified as video using only the extension. Gallery parsing
  now respects the audio role and recognizes audio extensions. Background music
  is not a selectable photo and does not trigger video inspection.
- Canonical `tiktok.com`/`www.tiktok.com` URLs matching `/@user/photo/<numeric-id>`
  use the existing gallery inspection directly, including URLs with tracking
  queries. A photo error is preserved instead of adding an unrelated video error.
  Other URLs retain automatic photo/video and mixed-post detection.
- New `process::execute_output` returns stdout and bounded stderr separately.
  Existing `execute`/`execute_captured` APIs retain their behavior for all callers.
  Gallery inspection reads diagnostics even on exit 0 and rejects logged partial
  extraction errors instead of trusting an incomplete album's index mapping.
  Nonfatal warnings do not discard valid albums. Existing output bounds,
  cancellation, URL validation and process-tree cleanup remain.
- The bundled Python launcher now propagates `main()`'s return code, matching the
  [upstream launcher](https://github.com/mikf/gallery-dl/blob/v1.32.16/gallery_dl/__main__.py).
  Existing installations using the old launcher are covered by stderr handling;
  rebuilding the launcher also fixes future bundled CLI error statuses.

## Checks

- Failing regression tests reproduced unwanted video fallback for the exact photo
  URL and the launcher's lost exit code before the fixes.
- `cargo test --locked`: 73 passed, 9 ignored. Five ignored functions are child
  process fixtures; four require real installed engines and were run below.
- Real-engine `media`/`preview` tests with `--include-ignored`: 24 passed.
- Gallery/process focused tests: 16 passed. Cases cover MP4/delegated background
  audio, photo-only selection/ranks, zero-exit diagnostics, partial errors, warnings,
  safe paths, captured failures, output bounds and cancellation.
- `cargo fmt --all -- --check`, Clippy across all targets with warnings denied,
  and debug build: passed.
- Both `npm.cmd run test:ui` suites passed. The exact TikTok photo URL has a
  successful photo/music fixture and a denied fixture. Neither invokes the video
  inspector; denial retains 403 without Unsupported URL or No supported photos.
  Existing generic photo/video/mixed, stale/cancelled, player and History tests pass.
- Native Windows WebView2, isolated data: a fixture matching the exact TikTok URL
  returned three local photo URLs and MP4 background audio. Rust produced three
  320px previews from real local HTTP images with the album Referer. All three
  photos were selected; no video inspection occurred. A zero-exit `[]`/403 fixture
  then displayed the real error without fallback. No page errors. These are
  controlled local fixtures, not successful live downloads from TikTok.
- Bundled gallery executable rebuilt with the corrected launcher. Two real CLI
  integration tests passed: local album downloads preserve selected photo ranks
  and file bytes; unsupported offline URLs return a nonzero status and stderr.
  Full native engine compatibility checks also passed.
- Python discovery: 25 tests, 23 passed, two optional installed-gallery tests
  skipped; both were explicitly run successfully above. Locale check: 27 resources,
  24 languages. An independent read-only review found no concrete regressions and
  independently ran the 16 gallery/process checks.

## UI delivery gate

Anti-slop During remains the user's selected mode. Existing UI UX Pro Max guidance
and ENERGY 2 / RHYTHM 1 / MOTION 1 direction are retained.

- Hard Gate PASS: no new navigation, visual assets, palette or decorative copy.
  The actual error is displayed as text through the existing error state. The
  revised flow ran in Chromium and native Windows; both theme modes, RTL, keyboard,
  reduced motion and widths 375 to 1440 remain covered by the passing suites.
  Existing text colors/focus styling remain unchanged. No live-download success
  or security/performance claim is fabricated. Product changes used source patches.
- Purpose Gate PASS: the single URL form and existing media preview layout remain.
  Existing gradients express the user's selected theme; media surfaces preserve
  readability. No additional effects, icons, badges, fonts or illustrations.
- Liveliness PASS: the retained dials match the themed workspace, photo previews
  and restrained loading motion. User media remains the focal point; spacing and
  the selected accent retain their existing roles. No unrelated redesign.
- Craftsmanship PASS: photo links now keep their correct engine route; background
  audio no longer causes a false video classification; zero-exit failures show
  their real diagnostics. Valid album warnings and generic mixed posts are tested.
  Empty, loading and error states remain usable and keyboard-accessible.

## Limits and artifact

This fixes routing, classification, error propagation and launcher exit status.
It does not establish that the site's live 403 has been resolved. The user confirmed
the link opens without login and previously downloaded through Torikomi; no browser
cookies/configuration were read or changed. No alternate service/proxy or access
challenge workaround was added. macOS execution was not checked on Windows.

Local native screenshots: `shared/desktop/build/previews/native-tiktok-photo-routing.png`
and `native-photo-access-error.png`; their images are synthetic test patterns.

Windows release build and NSIS packaging passed. Artifact:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,803,121 bytes.
`7z t` returned exit code 0. SHA256:

```text
46ebb3ccaa908a7a13df52a1f7bda2edb3c4d5e130cd6490c1e7605c3b381570
```

The local installer is unsigned (`NotSigned`). It was built and checked without
replacing the user's installed application or changing its data.
