# Compact folder checkboxes and removed playback history

The user requested a smaller Add to folder checkbox and removal of playback history.

The checkbox now measures 18×18 CSS pixels. Its enclosing label remains at least
48px high and can be clicked across the row. Long folder names wrap. The change is
scoped to collection checkboxes; settings switches retain their existing sizing.

Playback-history UI, recording, storage writes, handlers and three unused translated
labels were removed. Previously saved playback records for this device are stripped
on load while preserving valid volume/mute preferences. Previous retains only the
temporary navigation state needed during the current session. Download History,
favorites and the highlighted single-line play queue keep their behavior.

## Checks

- Both UI suites passed. Checks cover exact checkbox dimensions, the larger label
  target, Space and label-text toggles, saved folder membership, absence of history
  UI/storage, and legacy-record cleanup preserving 35% volume and mute.
- Existing queue, current-row highlighting, volume persistence, playback errors,
  photo/video viewing and narrow RTL/large-text checks remain passing.
- Native debug Windows WebView2 with isolated data passed 18×18 checkbox sizing,
  folder save/reload, actual MP3 Next/Previous and volume/mute, absent history,
  video/photo playback, Acrylic on/off and 200% text.
- Locale check: 27 resources and 24 languages. Both catalog tests and four locale
  generator tests passed. Read-only review found no confirmed blockers.
- Native dialog screenshot:
  `shared/desktop/build/previews/native-folder-membership.png`.

## UI delivery gate

UI UX Pro Max and anti-slop During remain active. Existing personal-media workspace
direction retained: ENERGY 2 / RHYTHM 1 / MOTION 1.

- Hard Gate PASS: measured checkbox and label targets, keyboard/label interaction,
  stored membership and removal/migration behavior were exercised in browser/native
  checks. Existing focus, themes, RTL, empty and error states remain.
- Purpose Gate PASS: native checkbox sizing corrects inherited text-input sizing.
  The requested removal adds no new decoration, copy, effects or dependencies.
- Liveliness PASS: existing artwork, queue accent and spacing retain their roles.
  The dialog keeps the user's folder name as its functional focus.
- Craftsmanship PASS: checkbox use and folder persistence work; playback history is
  removed throughout the UI/storage flow, with volume and media data preserved.

Native runtime checks used the debug Windows build. macOS runtime was not tested;
release verification covers compilation, packaging and archive integrity.

## Windows installer

Release build and NSIS packaging passed:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,808,476 bytes.
`7z t` passed with exit code 0. SHA256:

```text
97b86f2d1170a481134f6fad99003d1fb7abc09becc9779c4c99ee0d52a2db89
```

The installer is unsigned (`NotSigned`). The existing custom-Indonesian NSIS
translation warning remains. The user's installed app was not replaced.
