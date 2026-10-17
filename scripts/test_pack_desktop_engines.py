import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pack_desktop_engines


class BootstrapPackageTests(unittest.TestCase):
    def test_cli_manifest_default_is_repository_relative(self):
        with patch("sys.argv", ["pack", "--bundle", "prepared", "--output", "dist/engines", "--tag", "v2.0.0"]), \
             patch.object(pack_desktop_engines, "package", return_value={"targets": [{"tools": {}}]}) as package, \
             patch("builtins.print"):
            pack_desktop_engines.main()
        self.assertEqual(package.call_args.args[2], Path(pack_desktop_engines.__file__).resolve().parents[1] / "shared/desktop/src-tauri/bootstrap-engines.json")

    def test_pinned_downloads_include_verified_tools_and_complete_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "bundle"
            (bundle / "licenses").mkdir(parents=True)
            (bundle / "licenses/NOTICE").write_text("License text", encoding="utf-8")
            (bundle / "sources.json").write_text(json.dumps({"sources": {"ffmpeg": "https://ffmpeg.org/"}}))
            tools = {}
            for name in ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"):
                content = (name + " executable fixture").encode()
                (bundle / (name + ".exe")).write_bytes(content)
                tools[name] = {"file": name + ".exe", "version": "1.0.0",
                               "sha256": hashlib.sha256(content).hexdigest()}
            (bundle / "manifest.json").write_text(json.dumps({"schemaVersion": 1,
                "platform": "windows", "arch": "x86_64", "tools": tools}))
            output = root / "downloads"
            config = root / "app/bootstrap.json"
            value = pack_desktop_engines.package(bundle, output, config, "v2.0.0")
            target = value["targets"][0]
            self.assertIn("License text", target["notices"])
            for name, tool in target["tools"].items():
                archive = output / tool["url"].rsplit("/", 1)[1]
                self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), tool["downloadSha256"])
                self.assertEqual(archive.stat().st_size, tool["downloadSize"])
                original = gzip.decompress(archive.read_bytes())
                self.assertEqual(original, (bundle / (name + ".exe")).read_bytes())
                self.assertEqual(hashlib.sha256(original).hexdigest(), tool["sha256"])
                self.assertIn(tool["downloadSha256"][:12], archive.name)
            self.assertEqual(json.loads(config.read_text(encoding="utf-8")), value)
            tools["gallery-dl"]["sha256"] = "0" * 64
            (bundle / "manifest.json").write_text(json.dumps({"schemaVersion": 1,
                "platform": "windows", "arch": "x86_64", "tools": tools}))
            with self.assertRaises(ValueError):
                pack_desktop_engines.package(bundle, output, config, "v2.0.0")

    def test_invalid_release_tag_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            for tag in ("latest", "../v2.0.0", "v2.0.0?token=secret"):
                with self.assertRaises(ValueError):
                    pack_desktop_engines.package(Path(directory), output, output / "manifest.json", tag)
            self.assertFalse(output.exists())
