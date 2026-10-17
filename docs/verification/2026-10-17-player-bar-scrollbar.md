# Player dock and themed scrollbars

The fixed player previously subtracted 130px from the sidebar height regardless of
the actual player size. Its 160px content padding also ignored responsive and text
size changes, and the sidebar/player breakpoints disagreed between 650 and 760px.

The sidebar now fills the viewport. ResizeObserver measures the visible dock and
reserves its actual height for the sidebar footer, page content and scroll padding.
The narrow navigation keeps its natural height. A hidden dock reports zero height.

Desktop scrollbars have transparent tracks, rounded handles, theme-driven colors and
hover/pressed states. The 12px track leaves a 6px visible handle. Standard scrollbar
properties provide the non-WebKit fallback; forced colors retain system defaults.
No custom scrolling implementation or dependency was added. Scrollbar selector and
fallback behavior follows [MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar).

## Verification

- A regression assertion reproduced the sidebar gap before the fix.
- Browser UI suites pass, including player dimensions at widths 375/620/700/780/1000/1280,
  light/dark themes, up to 200% text, themed scrollbar styles, volume, playback and RTL.
  Mouse-wheel scrolling works and the final download action stays above the dock.
- Isolated native Windows debug WebView2 passes real MP3/video/photo playback,
  favorites, volume and existing regression checks. New assertions confirm the
  sidebar reaches the dock and the themed scrollbar uses the expected track size.
- Screenshots: `shared/desktop/build/previews/player-bar-scrollbar.png` and
  `shared/desktop/build/previews/native-player-bar-scrollbar.png`.
- The native screenshot was visually inspected: no exposed strip between the
  sidebar and dock; sidebar footer and player controls remain visible.

## UI delivery gate

UI UX Pro Max and anti-slop During; existing Torikomi artwork, gradients and compact
icon controls retained. ENERGY2 / RHYTHM1 / MOTION1.

- Hard Gate PASS: narrow layouts, large text, theme colors, player operation and
  scroll access covered by browser/native checks. Focus scroll padding reserves dock space.
- Purpose Gate PASS: measured spacing fixes the reported gap; scrollbar colors
  inherit the selected theme. No decorative assets, copy or effects added.
- Liveliness PASS: artwork and current media remain the focal point; the existing
  theme accent marks controls and scrollbar interaction.
- Craftsmanship PASS: fixed heights removed, hidden/visible dock changes handled,
  narrow navigation kept compact and actual native rendering inspected.

macOS native scrollbar rendering remains untested on this Windows host.

## Windows installer

Release compilation and NSIS packaging passed. Updated artifact:
`windows/dist/Torikomi_2.0.0_x64-setup.exe`, 4,110,171 bytes. `7z t` passed.
SHA256: `ffbf9eea77ced4248ae2adb8ef32e97fe270e1105cd9142a254fc3579c96ed5a`.
The installer remains unsigned; the existing Indonesian NSIS translation warning remains.
