# Desktop photo and Library previews

Scope: the shared Windows/macOS desktop interface and Rust preview service.
Anti-slop is installed in the user's Codex skills directory; the user selected
During mode for this session. This report covers the preview changes, not an audit
of the entire application or the mobile apps.

## Design direction

The user's references call for Discord-like selectable themes, visible gradients,
and a modern media application. Preserve that existing direction. ENERGY 2,
RHYTHM 1, MOTION 1: media and selection receive emphasis; repeated album tiles
support comparison; only pending work animates, with reduced-motion support.

Photos lead each album tile because the user needs to choose images rather than
read a repeated caption. Captions occupy two lines with the complete text in the
title attribute. Original dimensions and file types remain visible. Library uses
smaller thumbnails beside titles so browsing and file actions stay compact. Images
use contain sizing to preserve the complete composition. Uniform album tiles
represent equivalent selection choices; Library rows represent saved files.

The existing gradients express the user's theme selection, while solid media
surfaces keep images legible. Preview frames add no decorative icons, generated
artwork, glass layers, badges, shadows, or animation. Test screenshots use clearly
labelled local fixtures; the fixtures are not application content.

## Delivery gate

- Hard Gate PASS: the added product strings only describe loading and unavailable
  previews, translated into all 24 supported languages. No invented media,
  testimonials, metrics, claims, decorative emoji, or marketing text was added.
- Purpose Gate PASS: image-first album tiles and compact Library rows serve media
  selection and browsing. The sizing, caption limit, repeated layout, and retained
  theme treatment have written reasons above; previews add no cosmetic effects.
- Liveliness PASS: dials are explicit above. Actual media is the focal point;
  spacing separates thumbnails, captions, metadata, and actions. The existing theme
  accent marks selected photos and actionable controls, retaining Torikomi's visual
  direction without competing decoration.
- Craftsmanship PASS: browser tests exercised selection, failed previews and retry,
  photo/video Library previews, missing local files, RTL, both theme modes, larger
  text, and widths 375 to 1440. Native Windows WebView2 verified actual Rust IPC,
  HTTP image decoding with the album Referer, PNG data URLs under the unchanged
  CSP, rejected uninspected IDs, local photo/video previews after reload with no
  remote image requests, 200% text at 620px, and keyboard use of a Library action.

## Verification evidence

- `cargo fmt --all -- --check`: passed.
- `cargo test --locked`: 41 tests passed; four child-process fixture functions and
  the separate installed-FFmpeg integration test are deliberately ignored.
- Installed-FFmpeg integration test, explicitly enabled with `--ignored --exact`:
  passed; generated a real MP4 and decoded its thumbnail as a 320 by 180 PNG.
- `cargo clippy --locked --all-targets -- -D warnings`: passed.
- `npm.cmd run test:ui` and JavaScript catalog tests: passed.
- `python scripts/generate_locales.py --check`: 27 resources, 24 languages passed.
- Python script tests: 16 passed; one optional standalone gallery-engine test
  skipped in that suite.
- Release YAML parsed; its three desktop matrix targets explicitly run the
  installed-FFmpeg thumbnail test after engine preparation.
- The Apple Silicon FFmpeg source configuration explicitly enables zlib for PNG
  frame output with library autodetection disabled. The native CI test verifies
  that a frame can actually be extracted before packaging.
- Anti-slop's contrast formula checked 240 relevant text/background pairs over
  24 themes and both modes. The lowest ratio is 5.11:1. Album metadata now uses the
  foreground color so selected tiles also meet normal-text contrast.
- Final Windows release build and NSIS packaging passed after the contrast fix;
  7-Zip's installer integrity test passed. Output:
  `windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,531,187 bytes.
  SHA256: `ee1daab37fd4eb680f06e58bb3a7c3fcf0d6fc17035bde1e8bcbf3677c3ced79`.

Native screenshots are in `shared/desktop/build/previews/native-photo-previews.png`
and `native-library-previews.png`. The isolated native smoke harness and fixtures
are in the ignored `build/preview-native/` directory; no user profile was used.

## Limits

The supplied public TikTok album returned a verification challenge, HTTP 403, or
rate limiting during live inspection. Its actual CDN previews remain unverified.
The deterministic HTTP and local-file tests establish the preview path, not access
to a blocked site. Unsupported image formats and oversized or unavailable sources
show an unavailable state; users can retry or still select/download the originals.
Library entries imported without media files cannot display local thumbnails.
The shared macOS implementation has not been run on a Mac in this Windows session.
The local Windows installer is unsigned and was integrity-tested without replacing
the user's installed application.
