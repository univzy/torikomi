# Compact media actions, player page and setup persistence

The shared installer output has since been replaced by the
[dropdown and glass build](2026-10-16-dropdowns-and-glass.md).
The hash in this report records the earlier player-page build.

The desktop History and Favorites track cards now use icon actions with localized
accessible names and native title tooltips. Their click targets remain at least
44 by 44 CSS pixels. Photo Open uses an image symbol; audio/video Play uses the
existing transport symbol. Folder forms and primary navigation retain text.

Audio Play immediately opens a dedicated Now playing page inside the main workspace.
It uses the existing theme, fonts and real local artwork, with title, artist, album,
seek, volume, shuffle, repeat, favorites, collection and system-open actions.
The next-track list uses the existing context queue and displays up to 50 upcoming
tracks. Back and Escape return to the originating page. Audio keeps playing across
navigation; the smaller player appears on other pages. Automatic next-track playback
does not move a browsing user back to the player page.

An interrupted pending audio request can now be resumed from the smaller player
after viewing video. Stale media leases are still released, and video interruption
clears the pending player state. Page navigation focuses main without unwanted
focus scrolling, then returns to the top so the player heading and Back remain visible.

Automatic setup already verified and atomically saved the three selected engine
paths, but previously saved completion only through the separate Finish action.
It now saves completion in that same verified commit. A failed probe leaves both
completion and previous paths unchanged. Fresh installations still show onboarding;
Settings still allows manual setup. No user data or schema migration was added.

## Verification

- Both Chromium UI suites passed. They exercise icon names/tooltips/SVG/44px targets,
  keyboard activation, immediate page opening before a delayed native reply, Back,
  Escape, queue selection, seek/volume/shuffle/repeat, background playback and next,
  interrupted audio/video recovery, favorite toggles, folder selection and system
  open. Existing JSON, routing, media preview and sidebar checks remain passing.
- Light Arabic RTL at 375px and 150% text fits without horizontal overflow. The
  desktop dark player was also captured and visually checked. User-selected
  gradients, font preferences and reduced-motion loading remain supported.
- The full Rust suite passed: 75 passed, 9 ignored. Five ignored functions are
  child-process fixtures; four need separately prepared engines. New real-file
  setup regressions prove completion survives reload without Finish, and a failing
  FFmpeg probe preserves prior saved bytes/selection/completion for both a fresh
  and a previously completed installation. Fixtures use temporary executables.
- Clippy across all targets with warnings denied, cargo fmt check and both catalog
  tests passed.
- Native debug Windows WebView2 testing with isolated data passed real MP3 playback and
  seek, artwork and tags, player-page navigation, persistent playback, favorite
  folders across reload, H264/AAC video, and a photo over 2 MiB. The player fits
  at 620px with 200% text. No page errors occurred.
- A production-executable runtime attempt did not reach the fixture-state check:
  the data-directory override is intentionally debug-only. It was stopped before
  media UI actions. The native runtime results above apply to the debug build;
  production verification covers compilation, packaging and archive integrity.
- A read-only review found an interrupted-audio resume defect, which was fixed and
  covered by a passing browser regression. The corrected source review found no
  unresolved blockers.

## UI delivery gate

Anti-slop During and UI UX Pro Max remain active. Design read: a personal media
player for Torikomi users, with artwork-led listening controls and Torikomi's
existing themed workspace; ENERGY 2 / RHYTHM 1 / MOTION 1.

- Hard Gate PASS: the changed controls have real handlers, translated accessible
  names, visible focus and 44px targets. Keyboard, narrow RTL/large text, light/dark
  and real native playback were exercised. Empty artwork, pending preparation and
  playback recovery states remain. No new marketing claims, external assets or
  invented user content were added. Source changes used patches.
- Purpose Gate PASS: the user's theme gradients remain the brand surface. Artwork
  has one shadow to separate it from the background; other controls use existing
  borders. Icons describe download, playback, file opening, favorites and folders.
  Existing fonts and palette remain user controlled; no new decorative motion.
- Liveliness PASS: artwork and track title are the listening focal point, the
  selected accent marks playback and favorites, and spacing separates track details,
  controls and upcoming tracks. Dials retain the established workspace direction.
- Craftsmanship PASS: media cards consume less space while keeping labeled actions,
  playback uses a real page and remains available during browsing, verified setup
  survives restart, and interrupted preparation has a tested resume path. The layout
  follows Torikomi's existing visual identity and the user's music-player request.

## Scope

These changes affect the shared Windows/macOS desktop source. macOS execution and
packaging were not checked on Windows. Android and iOS UI were not changed by this
desktop task. Screenshots under shared/desktop/build/previews use clearly identified
local test patterns or fixture media. The installed application was not replaced.

## Windows artifact

Release compilation and NSIS packaging passed. Installer:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,816,876 bytes.
`7z t` returned exit code 0. SHA256:

```text
8e6c778f2a766b28e73280a612d61b7643f75502258539c060d61d14e6b4707b
```

Authenticode reports `NotSigned`. Packaging emitted the existing Tauri warning
about untranslated custom Indonesian NSIS messages. No installer was run over
the user's installed app.
