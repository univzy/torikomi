# Desktop media and engine updates verification

The later [automatic media detection update](2026-10-16-automatic-media-detection.md)
records the current installer and its checksum; the packaging details here describe
the earlier media/player build.

Scope: the approved Windows/macOS desktop features, Android/iOS preservation of
portable schema-2 records, and the separate engine publication workflow. This is
not a whole-repository design audit. Anti-slop During is the user's session choice.

## Result and data handling

History has video/photo/audio filters, local thumbnails and artist/album search.
Favorite audio supports named folders, independent memberships, rename/removal,
and a persistent player with Now playing, seek, volume, shuffle and repeat.
Video and photos open inside Torikomi. Downloaded audio uses real source tags and
preserves existing embedded metadata; supported containers also receive artwork.
Removing History/favorite records leaves the downloaded files in place.

Schema-1 data migrates to schema 2. Android and iOS retain the added metadata,
folders, memberships and deletion revisions on merge/export. Backups still exclude
local paths, media files, engine state and credentials. New v2 exports require an
updated reader; v1-only clients cannot import them.

The updater preserves manual paths, verifies managed packages and activates only
when native processing is idle. Tests cover manual nightly FFmpeg without adjacent
FFprobe and same-version repaired binaries with changed hashes. Older immutable
engine sets are deliberately retained to protect other running processes/manual
selections. Deployment and first engine-feed publication have not been performed.

## Verification evidence

- Rust `cargo test --locked`: 70 passed, 8 ignored. Four ignored tests are child
  process fixtures; four require installed engines and were explicitly run below.
- Real bundled engines, `cargo test --locked --test media --test preview --
  --include-ignored`: 24 passed. Generated MP3/M4A/FLAC/Opus tags and embedded
  artwork were inspected with FFprobe. WAV/raw AAC retained sidecar artwork.
  A direct tagged MP3 retained title, artist, album, album artist, genre, year and
  track; an untagged source did not acquire uploader/category/upload-date tags.
  Playback-copy generation, cancellation, ranges and full large-image reads passed.
- `cargo fmt --all -- --check` and `cargo clippy --locked --all-targets --
  -D warnings`: passed.
- `npm.cmd run test:ui`: both UI suites passed. Exercised History filters, artist
  search, favorites, folder create/rename/remove, membership removal, persistent
  real WAV playback, seek/volume/previous/next/shuffle/repeat, unsupported-playback
  recovery, photo viewer, keyboard dismissal, updater toggle/manual check,
  gradients, reduced motion, RTL, larger text and widths 375 to 1440.
- Added UI regressions prove a delayed audio response cannot restart underneath
  video, and repeat-off stops when remaining entries have no local file.
- Native Windows WebView2 with isolated app/WebView data: schema-1 migration,
  real local MP3 playback and seek to 5 seconds, embedded artwork, discovered tags,
  persistent player, MusicKu folder save/reload, H264/AAC video playback, and a
  complete 1280px BMP larger than 2 MiB all passed. Settings retained the disabled
  automatic-update preference. No page errors; 200% text fit at 620px.
- `python -m unittest discover -s scripts -p 'test_*.py'`: 23 tests, 22 passed and
  one optional installed-gallery test skipped. The bundled gallery compatibility
  test was also run separately with its real executable.
- Locale generator `--check`: 27 resources, 24 languages; two JavaScript catalog
  tests passed. Added strings have matching keys in every language.
- Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`: successful. 38 unit
  tests; zero lint errors. Existing lint/deprecation warnings remain.
- Both workflow YAML files parsed. The release workflow now explicitly runs all
  media/preview tests with bundled FFmpeg and yt-dlp on desktop runners.
- Live upstream resolution, FFmpeg source signature verification, and real Windows
  feed gzip packaging/hash/license validation passed in the packaging workstream.
  Complete native candidate source builds were not run on this Windows workstation.

## Design direction and delivery gate

ENERGY 2 / RHYTHM 1 / MOTION 1 follows the user's Discord-style themes and
Spotify-style playback request. Torikomi keeps its own selectable palettes, sidebar,
typography and file actions. Artwork is the focus of Now playing; History uses
compact repeated rows for equivalent records. Spacing separates media, information
and controls. Only pending operations animate, respecting reduced motion.

### Hard Gate

- R-02 PASS: the 62 added English media/update labels contain no em dashes;
  technical version placeholders do not introduce prose punctuation.
- R-03 PASS: UI suites checked widths 375 to 1440, RTL and 150% text; native
  Windows checked 200% text at 620px without horizontal document overflow.
- R-17/R-18/R-36/R-38 PASS: no testimonials, invented usage metrics, fabricated
  customers or unsupported security/performance claims were introduced. Synthetic
  media is explicitly labelled as test fixtures and is not seeded into the product.
- R-23 PASS: the user explicitly requested History, Favorites, folders and a
  Spotify-style player. The existing logo is retained; no visual assets were created.
- R-24 PASS: all four sidebar destinations resolve to existing page sections.
- R-25 PASS: computed foreground/muted text contrast over panel, background and
  both gradient endpoints for every light/dark preset; minimum 4.60:1 across
  384 pairings, with no failures. No new foreground/background palette was added.
- R-26 PASS: the added controls bind to native IPC or real player/dialog actions;
  UI click-through and Rust validation tests cover their behavior.
- R-27 PASS: empty collections, missing artwork/files, preview loading/retry,
  unsupported media and pending/failed/unpublished updates have visible states.
- R-28 PASS: no FAQ was added.
- R-32 PASS: native buttons/forms/ranges/dialogs retain visible focus styling;
  keyboard activation and Escape dismissal were exercised.
- R-33 PASS: product edits were made with source patches; the locale generator
  only generates its declared translation resources.
- R-34 PASS: existing theme tests and light RTL media tests passed. All preset
  text pairings were measured in both appearance modes.
- R-35 PASS: browser click-through, native Windows playback and a native debug
  build ran successfully. OS-level system-player/file-dialog integration remains
  a platform check, not a claim of automated coverage.
- R-37 PASS: the user's references and retained design direction were recorded
  before implementation in the approved spec and implementation plan.

### Purpose Gate

- R-01/R-10/R-12/R-13 PASS: existing gradients express user-selected themes;
  solid media surfaces keep content readable. No additional glass layers, large
  shadows or glows were added by these features.
- R-04 PASS: heart denotes favorite, transport icons control playback, and folder
  actions organize audio. Icons carry their content's meaning and accessible labels.
- R-06 PASS: retained type preferences preserve Torikomi's identity and readability;
  no new display face or decorative monospace block was introduced.
- R-07/R-08/R-09/R-22 PASS: no decorative grids, blanket arrows, promotional
  badges or stock illustrations were introduced.
- R-14 PASS: History rows represent equivalent records; folder tiles summarize
  collections; Now playing gives artwork and current-track information priority.
- R-19 PASS: no decorative entrance/movement effects were added. Loading motion
  remains tied to real pending work and respects reduced-motion preferences.

### Liveliness

- Dials PASS: ENERGY 2 / RHYTHM 1 / MOTION 1 are declared and match the existing
  themed workspace, repeated file rows and restrained loading motion.
- Focal point PASS: thumbnails lead browsing, folder names lead collections, and
  artwork/title lead Now playing; screenshots were inspected directly.
- Spacing PASS: gaps separate the media column, metadata and file actions; the
  persistent player has reserved document space and wraps at narrow widths.
- Accent PASS: the user's selected theme accent marks active navigation,
  favorites and playback state. Artwork colors come from the user's media.
- Identity PASS: selectable gradient palettes, Torikomi typography/sidebar and
  local-file actions remain consistent across the new pages.
- Design Read PASS: approved interaction patterns and direction preceded UI work.

### Craftsmanship and quality locks

- C-1/R-31 PASS: layout, artwork sizing, controls, spacing and theme treatment have
  the product-specific reasons above.
- C-2 PASS: folders, favorites, playback and engine settings have functioning
  handlers; unsupported formats expose recovery/system-app choices.
- C-3/R-05 PASS: sections serve browsing, collections, current playback and engine
  maintenance; no landing-page/template filler was added.
- C-4/R-21 PASS: both appearance modes, RTL, keyboard use, responsive layouts and
  empty/error/pending states were exercised within the platform limits below.
- C-5/R-16 PASS: labels describe implemented actions and actual local state.
- R-11/R-15/R-29 PASS: existing spacing/type/palette tokens are reused, with the
  current one-accent theme model and media-sized row/cover hierarchy.
- R-20/R-30 PASS: familiar audio controls follow the user's request while the
  existing Torikomi navigation, theme selection and download workflow remain.

## Screenshots

Local evidence under `shared/desktop/build/previews/` includes
`native-media-history.png`, `native-media-favorites.png`, `native-now-playing.png`,
`native-media-video.png`, `native-media-photo.png` and `media-history.png`.
They contain synthetic test patterns, not real user media.

## Limits and release status

macOS/iOS builds, Swift tests, Apple signing/notarization and device playback were
not run on Windows. Android compiled and passed tests/lint but was not exercised
on a physical device. New mobile player/favorites screens are outside the approved
desktop scope; mobile schema preservation is included. External-site availability
and authenticated downloads are not guaranteed. The live TikTok album remains
blocked by upstream challenge/rate limits and is not claimed as verified.

The engine workflow has not been published/dispatched and no first feed is live.
The updater reports no published package and retains installed tools until one is
available. Installation resources remain the pinned versions documented in
`docs/platforms.md`. Photos over 32 MiB use the system-app fallback; a manual
FFmpeg setup needs adjacent FFprobe for media probing/playback-copy preparation.

Windows release packaging completed successfully with Rust/Tauri and NSIS.
Copied artifact: `windows/dist/Torikomi_2.0.0_x64-setup.exe` (80,799,864 bytes).
`7z t` returned exit code 0. SHA256:

```text
d4edf714a179d0a143340448cb3e2f6820c7679de7b916bb854ac546511d8ba5
```

The local installer is unsigned (`Get-AuthenticodeSignature`: `NotSigned`). It
was built and integrity-tested without installing over or closing the user's
existing application. Windows may show its ordinary publisher warning.
