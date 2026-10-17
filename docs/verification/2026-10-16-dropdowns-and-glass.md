# Dropdown layout and native glass

The shared installer output has since been replaced by the
[player volume/history build](2026-10-17-player-volume-history.md).
The hash below records the earlier dropdown/glass build.

The user requested neater dropdowns and working window transparency.

Dropdowns retain native selection, keyboard navigation and accessible labels.
Settings, welcome-language and audio-format controls now share a 48px minimum
height, inset SVG chevron, logical padding for RTL and consistent spacing.
The text-size value sits beside its label, above the slider, so its row aligns
with the neighboring Layout dropdown.

The previous Windows native effect was Mica, and the frontend glass gradient was
85% opaque. Windows now requests Acrylic, with a transparent WebView/window and
an 18% theme-gradient tint. Cards and the sidebar retain 94% panel color for
readability. There is no duplicate CSS sidebar blur: the native compositor handles
the backdrop. Disabling glass restores the opaque theme and clears the native
effect. The Windows 11 22H2+ availability gate and macOS Sidebar vibrancy remain.
See the [Tauri window-effect configuration](https://v2.tauri.app/reference/config/)
and [window/background APIs](https://docs.rs/tauri/latest/tauri/webview/struct.WebviewWindow.html).

Exposed Tauri window-theme/background/effect errors now reach the existing error
message instead of being discarded. Tauri internally discards some underlying
platform effect errors; the Windows runtime check below verifies actual DWM state.

## Checks

- The dropdown regression first failed on the old 46px controls. Both final
  Chromium UI suites pass, including native select keyboard changes, persisted
  selection, new control size/arrows, glass on/off, unsupported-glass fallback,
  and emulated reduced transparency restoring opaque surfaces. Existing player,
  photo/video routing, JSON backup and localization interactions still pass.
- Widths 375, 620, 1024 and 1440, Arabic RTL, German labels and 150% text remain
  covered by the UI suite. Settings screenshots were visually inspected.
- Contrast was calculated for all 24 themes in both modes, compositing 94% panel
  color over both black and white. The lowest muted-text ratio was 4.661:1;
  foreground text also exceeded 4.5:1. Native dropdown backgrounds remain opaque.
- Native Windows 11 build 26200, debug WebView2 with isolated data: glass on
  produced DWM system-backdrop attribute 38 = 3 (Acrylic/transient); off produced
  1 (none). The query used only the PID of the test app. Windows transparency was
  enabled; no Windows personalization preferences were changed.
- Native regression checks also passed actual MP3 playback/seek/artwork/tags,
  player navigation, favorite folders across reload, H264/AAC video and a photo
  larger than 2 MiB, plus 200% text at 620px. No page errors.
- Full Rust suite passed: 75 passed, 9 ignored. The ignored cases are five
  subprocess fixtures and four prepared-engine integrations. Focused native app
  tests, formatting, Clippy with warnings denied and both catalog tests passed.
- Independent read-only review found no unresolved blockers.

## UI delivery gate

Anti-slop During and UI UX Pro Max remain active. Retained design direction:
personal media workspace with the user's theme colors and native glass;
ENERGY 2 / RHYTHM 1 / MOTION 1.

- Hard Gate PASS: native selects preserve labels and keyboard operation; all six
  dropdowns keep real existing behavior. RTL/large-text layouts and glass fallback
  were exercised. Native activation/removal was measured, not inferred from a CSS
  class. Source patches added no fabricated content or marketing claims.
- Purpose Gate PASS: transparency is explicitly requested by the user. One native
  backdrop supplies blur, with readable tinted content layers. The user's gradient
  remains a subtle theme tint; chevrons identify operable dropdowns. No additional
  animation, fonts, palettes, decorative assets or dependencies.
- Liveliness PASS: theme choices remain the Settings focal point; consistent field
  spacing and aligned labels establish rhythm. The selected accent retains focus
  and selection roles. Glass preserves the existing workspace identity.
- Craftsmanship PASS: dropdowns remain native and functional, reduced transparency
  restores solid surfaces, glass off removes the DWM effect, and contrast remains
  adequate for panel/sidebar text. Actual playback and data persistence regressions
  remain passing.

## Limits

Runtime verification used the debug Windows build with an isolated data directory.
Production verification covers compilation, packaging and archive integrity.
macOS execution was not tested on Windows; native vibrancy remains the existing
implementation. Windows can control material appearance through its own display
and accessibility settings. No installer was run over the installed user app.

Local native screenshot:
`shared/desktop/build/previews/native-settings-dropdowns.png`.
WebView captures do not include the desktop compositor backdrop, so they are
layout evidence; the DWM query verifies native material activation.

## Windows installer

Release compilation and NSIS packaging passed. Artifact:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 80,811,976 bytes.
`7z t` returned exit code 0. SHA256:

```text
6c40916167273bedb777a4767159f8f1638d196b27ad842987d3f3be7c68a734
```

The installer is unsigned (`NotSigned`). Packaging retains the existing Tauri
warning about untranslated custom Indonesian NSIS messages.
