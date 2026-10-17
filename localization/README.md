# Translations and themes

`locales/en.json` defines the message IDs. Each of the 24 languages listed in
`languages.json` has a complete JSON file with those same IDs. Edit these source
files, then run from the repository root:

```sh
python scripts/generate_locales.py
python scripts/generate_locales.py --check
python -m unittest discover -s scripts -p test_generate_locales.py
```

The generator uses Python's standard library. It writes Android string resources
and its Kotlin catalog, the Tauri Windows/macOS JavaScript catalog, and the iOS Swift
package resource. Generated files are committed so platform builds need no
translation service or network request. Never edit a generated file directly.

To add a language, add its native display name, BCP 47 code, Android resource
directory and RTL flag to `languages.json`, then translate every English message
into `locales/<code>.json`. Preserve formatting tokens (`%1$s`, `%d`, `%%`) and
newlines. Strings are ordinary JSON text; the generator handles Android escaping.
The check rejects missing/extra IDs, blank translations, changed placeholders,
damaged Unicode and stale generated output. CI runs it before platform builds.

`android-keys.json` selects Android resources; `desktop-keys.json` maps desktop
call sites to shared IDs. iOS resolves its existing English call sites through
the generated English-to-ID index. New appearance controls use IDs directly.

`shared/catalog/themes.json` defines four neutral themes and 20 color themes.
Each includes its swatch, light/dark semantic colors, and visible background
gradient stops. The generator checks text and secondary text against gradient stops,
and text/secondary text/accent against both surfaces (at least 4.5:1).
Existing theme IDs and the schema-1 document format are preserved for JSON backups.
Older app builds should be upgraded before importing newer themes.

New translations are drafts and need native-speaker review, especially technical
terms and RTL layouts. This script generates platform resources; it does not
invent translations or copy English into missing language entries.
