# Automatic media detection

The later [TikTok photo-routing correction](2026-10-16-tiktok-photo-routing.md)
contains the current installer checksum and additional regression evidence.
This record describes the earlier general automatic-detection build.

Scope: shared Windows/macOS desktop link form. The user requested removal of the
Video/Photos/Albums switch and engine-explanation text. Existing Torikomi themes,
type settings and file actions are retained. Anti-slop During remains the user's
session choice; UI UX Pro Max interaction/accessibility guidance is reused.

## Behavior

Paste a link and press Show formats. The existing photo inspector runs first.
Photo-only results open the photo chooser directly. If photos are unsupported,
the existing video/audio inspector runs automatically. Mixed results can display
both panels. A video failure leaves successfully inspected photos available;
intermediate photo errors stay hidden when video detection succeeds.

Editing a URL invalidates its in-flight result and prevents another fallback for
that old URL. Cancellation stops fallback. Existing process limits, URL validation,
preview authorization and exact format selections are retained.

History redownloads reuse an entry only for the same URL and media kind. A saved
link or a different media kind creates a separate media entry, preserving the
original record and its favorites rather than attempting an invalid replacement.

## Verification

- Red/green UI checks initially failed because two mode controls remained and a
  saved link's ID was incorrectly passed as the replacement media ID. Both pass
  after the source changes.
- `npm.cmd run test:ui`: both suites passed. Added cases cover photo-only, video
  fallback, mixed posts, partial failure, complete failure, cancellation, stale
  responses, saved-link downloads and same/different-kind History redownloads.
  Existing metadata/player/favorite, layout, theme, locale and preview tests pass.
- Native Windows WebView2: photo metadata fixture produced a real HTTP preview
  with its Referer; the same form then inspected a locally served MP4 using the
  real bundled yt-dlp after the photo inspector rejected it. No mode buttons or
  engine-explanation paragraph remained. No intermediate error or page error.
  Existing preview authorization, local Library previews, keyboard and 200% text
  checks also passed in that native run.
- `cargo build --locked`: passed with the new embedded frontend. No Rust, JSON
  schema, dependencies or native IPC contracts changed in this task.
- Native screenshot inspected: `shared/desktop/build/previews/native-automatic-detection.png`.
- Existing release CI runs both updated UI suites through `test:ui`; no additional
  job or platform-specific implementation is needed for this shared UI change.

## Anti-slop delivery gate

- Hard Gate PASS (R-02/R-03/R-17/R-18/R-23/R-24/R-25/R-26/R-27/R-28/R-32 through
  R-38): no new product copy, palettes, visual assets, claims or navigation were
  introduced. The requested controls/copy were removed. Existing labels/focus
  states remain; both theme modes, RTL, larger text and widths 375 to 1440 passed
  the UI suites. Real loading and error states remain. Native execution and browser
  click-through cover the changed flow. Product edits used source patches.
- Purpose Gate PASS (R-01/R-04/R-06 through R-10/R-12 through R-14/R-19/R-22):
  retained gradients express the user's theme; media surfaces preserve readability.
  The form asks for a URL instead of asking users to select an engine's media type.
  No decorative effects, illustrations, badges or new icons were added.
- Liveliness PASS: ENERGY 2 / RHYTHM 1 / MOTION 1 is retained. The link input is
  the initial focal point; detected photos/formats become the next focal point.
  Existing theme accents, row spacing, typography and restrained loading motion
  preserve the established identity. This direction preceded implementation.
- Craftsmanship PASS (C-1 through C-5; R-05/R-11/R-15/R-16/R-20/R-21/R-29 through
  R-31): one real detection flow replaces the removed choice. All remaining
  controls retain actions, error/loading states and exact format choices. Partial
  failure and stale-result tests prove the flow stays usable. No placeholder
  functionality or unrelated layout section was added.

## Limits

Remote site availability/login restrictions still apply. Detection can require
both existing inspectors; their existing bounded timeouts remain. The fetch button
starts inspection, while media-type selection is automatic. No fetch is triggered
for partially typed or merely edited URLs. Live TikTok availability is not claimed.
macOS execution was not checked on Windows. The local Windows installer is unsigned.

Windows release build and NSIS packaging passed. Latest artifact:
`windows/dist/Torikomi_2.0.0_x64-setup.exe` (80,798,650 bytes).
`7z t` returned exit code 0. SHA256:

```text
5ad7d3802cf57112dca20adf45000b321ac0c4b8dc6a92424b975b40e57e0a72
```
