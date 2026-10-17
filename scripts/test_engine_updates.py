import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import engine_updates as updates
import publish_engine_feed as publication


class EngineUpdateTests(unittest.TestCase):
    def test_stable_versions_reject_release_candidates_and_arbitrary_commands(self):
        self.assertEqual(updates.stable_version("v1.32.16"), "1.32.16")
        for version in ("1.0rc1", "nightly", "9.0.2;echo", "1..2", ""):
            with self.assertRaises(ValueError):
                updates.stable_version(version)

    def test_checksum_requires_exact_unique_upstream_filename(self):
        digest = "a" * 64
        self.assertEqual(updates.checksum(f"{digest}  yt-dlp.exe\n", "yt-dlp.exe"), digest)
        for text in (f"{digest}  other.exe", f"{digest}  yt-dlp.exe\n{digest}  yt-dlp.exe", "bad  yt-dlp.exe"):
            with self.assertRaises(ValueError):
                updates.checksum(text, "yt-dlp.exe")

    def test_packaging_hashes_bounded_individual_assets_and_all_license_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "licenses").mkdir()
            (bundle / "licenses/NOTICE").write_text("Real source notice")
            (bundle / "sources.json").write_text(json.dumps({"sources": {"ffmpeg": "https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz"}}))
            tools = {}
            for tool in updates.TOOLS:
                (bundle / (tool + ".exe")).write_bytes(b"executable fixture")
                tools[tool] = {"file": tool + ".exe", "version": "9.0.2", "sha256": updates.digest(bundle / (tool + ".exe"))}
            (bundle / "manifest.json").write_text(json.dumps({"schemaVersion": 1, "platform": "windows", "arch": "x86_64", "tools": tools}))
            lock = {"revision": "engines-20261016-abcdef", "minAppVersion": "2.0.0"}
            manifest = updates.package(bundle, root / "assets", lock)
            self.assertEqual(set(manifest["tools"]), set(updates.TOOLS))
            for name, tool in manifest["tools"].items():
                compressed = root / "assets" / tool["asset"]
                self.assertEqual(gzip.decompress(compressed.read_bytes()), b"executable fixture")
                self.assertEqual(tool["compressedSha256"], updates.digest(compressed))
                self.assertEqual(tool["compressedSize"], compressed.stat().st_size)
            notices = json.loads((root / "assets" / manifest["notices"]["asset"]).read_text())
            self.assertEqual(notices["licenses"]["NOTICE"], "Real source notice")
            updates.validate_assets(root / "assets", expected_targets={"windows-x86_64"})
            compressed.write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                updates.validate_assets(root / "assets", expected_targets={"windows-x86_64"})

    def test_lock_trust_boundary_rejects_unpinned_sources_and_bad_hashes(self):
        lock = {"schemaVersion": 1, "versions": {"yt-dlp": "2026.08.19", "gallery-dl": "1.32.16", "ffmpeg": "9.0.2", "ffprobe": "9.0.2"},
                "revision": "engines-test", "minAppVersion": "2.0.0", "requirementsSha256": "a" * 64,
                "gallery": {"url": "https://files.pythonhosted.org/packages/a/gallery_dl-1.32.16-py3-none-any.whl", "sha256": "b" * 64},
                "yt": {platform: {"url": f"https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/{asset}", "sha256": "c" * 64} for platform, asset in [("windows", "yt-dlp.exe"), ("macos", "yt-dlp_macos")]},
                "ffmpeg": {"url": "https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz", "sha256": "d" * 64, "signingKey": updates.FFMPEG_KEY}}
        updates.validate_lock(lock)
        for key, value in [("url", "https://evil.example/ffmpeg-9.0.2.tar.xz"), ("sha256", "z" * 64), ("signingKey", "0" * 40)]:
            changed = json.loads(json.dumps(lock))
            changed["ffmpeg"][key] = value
            with self.assertRaises(ValueError):
                updates.validate_lock(changed)

    def test_publisher_rejects_other_repositories_and_immutable_published_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "candidate-lock.json").write_text('{"revision":"engines-test"}')
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "other/project"}), patch.object(subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "pinned repository"):
                    publication.publish(root, "engines-test")
                run.assert_not_called()
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": updates.REPOSITORY}), patch.object(updates, "validate_assets"), patch.object(updates, "validate_lock", side_effect=lambda value: value), patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '{"isDraft":false,"isPrerelease":true}', '')) as run:
                with self.assertRaisesRegex(ValueError, "immutable"):
                    publication.publish(root, "engines-test")
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.args[0][1:3], ["release", "view"])

    def test_failed_uploaded_asset_verification_leaves_the_release_in_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "candidate-lock.json").write_text('{"revision":"engines-test"}')
            (root / "fixture.gz").write_bytes(b"original")
            calls = []

            def run(arguments, **kwargs):
                calls.append(arguments)
                operation = arguments[2]
                if operation == "view":
                    return subprocess.CompletedProcess(arguments, 1, '', 'not found')
                if operation == "download":
                    downloaded = Path(arguments[arguments.index("--dir") + 1])
                    for source in root.glob("*.json"):
                        shutil.copyfile(source, downloaded / source.name)
                    (downloaded / "fixture.gz").write_bytes(b"corrupt")
                return subprocess.CompletedProcess(arguments, 0, '', '')

            with patch.dict(os.environ, {"GITHUB_REPOSITORY": updates.REPOSITORY, "GITHUB_SHA": "a" * 40}), patch.object(updates, "validate_assets"), patch.object(updates, "validate_lock", side_effect=lambda value: value), patch.object(subprocess, "run", side_effect=run):
                with self.assertRaisesRegex(ValueError, "Uploaded release asset differs"):
                    publication.publish(root, "engines-test")
            self.assertEqual([arguments[2] for arguments in calls], ["view", "create", "upload", "download"])
            self.assertIn("--draft", calls[1])
            self.assertIn("--prerelease", calls[1])


if __name__ == "__main__":
    unittest.main()
