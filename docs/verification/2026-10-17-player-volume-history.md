# Player layout, volume, queue and playback history

The user subsequently requested playback-history removal. The current installer
is documented in [the checkbox/history-removal report](2026-10-17-favorite-checkbox-and-history-removal.md).
This report and its hash record the earlier build.

The user requested a tidier player, usable volume control, upcoming tracks and
playback history. During implementation they specified single-line music rows and
a different color for the current track.

## Result

- The artwork and track details share one player surface, followed by a full-width
  timeline and a transport/volume row. The user's theme, font and glass settings remain.
- Volume uses 0–100% with 1% steps, a filled slider, visible percentage and a labeled
  mute button. The quieter end uses a squared gain curve for finer listening control.
  Both players follow actual audio volume/mute changes, and unmute restores the
  last nonzero level. Volume/mute survive navigation, track changes and reload.
- The play queue includes the current track and its upcoming tracks. Each item has
  artwork, title, artist, duration and playback icon on one horizontal line.
  The current item has an accent border/background, current-state accessibility
  attribute and an icon that changes between Play and Pause. Selecting it toggles
  playback without reopening the file. Keyboard focus survives list updates.
- A deterministic shuffled order is shared by the displayed queue and Next.
  Next is disabled at the end when repeat-all is off; unavailable media are skipped.
- Playback history records successful track starts and shows local artwork and
  playback times. Pause/resume or reopening the same current track does not add
  duplicate records. Previous includes manually selected tracks, and history items
  can be replayed. Clearing playback history preserves downloads, favorites,
  current playback and volume.
- Local history retains up to 50 records per device in WebView storage. Saved
  values have type/size/range bounds; malformed storage falls back without blocking
  playback. Volume/history are local UI state and are excluded from JSON backups.
- Eight new labels are translated in all 24 languages. Existing mobile resources
  were regenerated from the same source; this task changes desktop player behavior.

## Verification

- Both final Chromium UI suites passed. New checks cover 0/5/35/100% gain, mute
  restoration, native volumechange synchronization, both player surfaces and restart.
  For example 35% produces audio gain 0.1225; zero is silent and 100% is gain 1.
- Real audio fixture playback verifies successful-start history, pause/resume and
  same-track deduplication, replay, Previous/Next, shuffle-preview consistency,
  unavailable media, failed/stale opens, nondestructive clearing and thumbnail reuse.
- Storage checks cover reload, invalid JSON, invalid saved types/ranges/records and
  the 50-record cap. Existing JSON, engine setup, photo/video routing, favorites
  and viewer regressions remain passing.
- Music-row geometry is checked at desktop size and 375px Arabic RTL with 150%
  text: artwork, title, artist and icon share the same horizontal row, and text
  does not wrap or overflow. Current-track colors move with playback; row Pause,
  keyboard Enter resume and retained focus are exercised.
- Native debug Windows WebView2 checks passed with isolated data: two real MP3
  files, title/artist/artwork, 35% gain, mute/unmute, Next/Previous, playback history,
  volume/history after reload, favorites, H264/AAC video and a photo over 2 MiB.
  Native Acrylic activation/removal and 200% text at 620px still pass. No page errors.
- Catalog tests passed. Locale generation check passed: 27 resources, 24 languages;
  all four generator tests and explicit eight-key translation completeness passed.
- Independent read-only reviews found no confirmed blockers in playback state,
  persistence, row layout, highlighting, focus or queue navigation.

## UI delivery gate

Anti-slop During and UI UX Pro Max remain active. Design read: Torikomi's personal
media workspace, with an artwork-led player and readable single-row music lists;
ENERGY 2 / RHYTHM 1 / MOTION 1.

- Hard Gate PASS: real media starts and native controls were exercised; accessible
  names, current-track state, keyboard focus, range values and empty/error states
  are covered. Narrow RTL and large text fit without horizontal overflow. The
  lowest accent-title contrast on the active-row background across all 24 themes
  and both modes is 4.639:1. No fictitious product content or external assets.
- Purpose Gate PASS: a single player surface groups track information and controls;
  full-width single-line rows give music titles room. Accent color indicates the
  actual selected track, and Play/Pause reflects real audio state. Existing user
  gradients remain; no new motion, font, image service or dependency.
- Liveliness PASS: real artwork remains the focal point; spacing separates playback
  from queue/history, and the selected theme connects the active track to Torikomi.
  The retained dials fit a practical listening interface.
- Craftsmanship PASS: playback survives navigation; volume has fine control and
  useful feedback; displayed queue order matches Next; history logs successful
  starts and has a working clear action. Thumbnail reuse, focus preservation and
  malformed-storage handling are tested.

## Limits

Native runtime testing uses the debug Windows build and isolated app data.
Production checks cover compilation, packaging and archive integrity. macOS
runtime was not tested on Windows. History is local to the WebView profile; it
does not sync through JSON backups or between devices.

Screenshots in `shared/desktop/build/previews/` include
`native-now-playing.png`, `player-page.png` and `player-page-light-rtl.png`.
They contain synthetic fixture media. WebView screenshots demonstrate layout;
they do not capture the native desktop compositor.

## Windows installer

Final release compilation and NSIS packaging passed. Artifact:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,802,893 bytes.
`7z t` passed with exit code 0. SHA256:

```text
a35eb8e271e8801e695f5f27188dccd5f13ab693d27740a54ba038d08fc03bdc
```

The installer is unsigned (`NotSigned`). The existing custom-Indonesian NSIS
translation warning remains. It was not installed over the user's app.
