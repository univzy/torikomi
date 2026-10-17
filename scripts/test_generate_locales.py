import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('generator', ROOT / 'scripts/generate_locales.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CatalogTests(unittest.TestCase):
    def test_missing_and_changed_placeholders_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing'):
            generator.validate_messages({'count': '%1$d files'}, {}, 'test')
        with self.assertRaisesRegex(ValueError, 'placeholder'):
            generator.validate_messages({'count': '%1$d files'}, {'count': '%s files'}, 'test')
        generator.validate_messages({'count': '%1$d files'}, {'count': '%1$d berkas'}, 'test')
        with self.assertRaisesRegex(ValueError, 'newline'):
            generator.validate_messages({'sample': 'A\nB'}, {'sample': 'AB'}, 'test')

    def test_android_escaping(self):
        import xml.etree.ElementTree as ET
        encoded = generator.android_xml({'sample': 'A & B\nDon\'t say "hi" \\'} , ['sample'])
        value = ET.fromstring(encoded).find('string').text
        self.assertEqual(value, 'A & B\\nDon\\\'t say \\"hi\\" \\\\')

    def test_every_palette_has_readable_text(self):
        themes = json.loads((ROOT / 'shared/catalog/themes.json').read_text(encoding='utf-8'))
        self.assertEqual(len(themes), 24)
        for theme in themes:
            for mode in ('light', 'dark'):
                colors = theme[mode]
                for surface in ('background', 'panel'):
                    for ink in ('foreground', 'muted', 'accent'):
                        self.assertGreaterEqual(generator.contrast(colors[ink], colors[surface]), 4.5, f'{theme["id"]}/{mode}/{surface}/{ink}')
                for surface in ('gradientStart', 'gradientEnd'):
                    for ink in ('foreground', 'muted'):
                        self.assertGreaterEqual(generator.contrast(colors[ink], colors[surface]), 4.5, f'{theme["id"]}/{mode}/{surface}/{ink}')
                if theme['group'] == 'color':
                    self.assertNotEqual(colors['gradientStart'], colors['gradientEnd'])

    def test_generation_is_deterministic_and_includes_all_platforms(self):
        outputs = generator.outputs()
        self.assertEqual(outputs, generator.outputs())
        self.assertIn(ROOT / 'shared/desktop/ui/catalog.js', outputs)
        self.assertIn(ROOT / 'ios/TorikomiCore/Resources/catalog.json', outputs)
        self.assertIn(ROOT / 'android/src/main/java/univzy/torikomi/data/model/AppCatalog.kt', outputs)


if __name__ == '__main__':
    unittest.main()
