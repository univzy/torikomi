import gzip
from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path
import tempfile
import tarfile
import subprocess
import sys
import unittest

import prepare_desktop_engines as engines


class DesktopEngineTests(unittest.TestCase):
    def test_gallery_entrypoint_preserves_engine_exit_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root / "entry.py").write_text(engines.GALLERY_ENTRYPOINT)
            (root / "gallery_dl.py").write_text("def main():\n    return 7\n")
            result=subprocess.run([sys.executable,str(root / "entry.py")],capture_output=True)
            self.assertEqual(result.returncode,7)

    def test_native_target_rejects_cross_builds_and_unsupported_hosts(self):
        self.assertEqual(engines.native_target("Windows", "AMD64"), ("windows", "x86_64"))
        self.assertEqual(engines.native_target("Darwin", "arm64"), ("macos", "aarch64"))
        self.assertEqual(engines.native_target("Darwin", "x86_64"), ("macos", "x86_64"))
        for system, arch in [("Linux", "x86_64"), ("Windows", "ARM64"), ("Darwin", "i386")]:
            with self.subTest(system=system, arch=arch), self.assertRaises(ValueError):
                engines.native_target(system, arch)

    def test_bad_hash_never_replaces_existing_download(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "asset"
            destination.write_bytes(b"previous")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                engines.write_verified(io.BytesIO(b"wrong"), destination, "0" * 64, 10)
            self.assertEqual(destination.read_bytes(), b"previous")
            self.assertFalse(destination.with_suffix(".part").exists())

    def test_download_size_is_bounded_without_content_length(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "asset"
            with self.assertRaisesRegex(ValueError, "size"):
                engines.write_verified(io.BytesIO(b"too big"), destination, "0" * 64, 3)
            self.assertFalse(destination.exists())

    def test_gzip_expansion_limit_preserves_previous_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "archive.gz"
            target = Path(directory) / "binary"
            target.write_bytes(b"previous")
            source.write_bytes(gzip.compress(b"large" * 100))
            with self.assertRaisesRegex(ValueError, "size"):
                engines.expand_gzip(source, target, 50)
            self.assertEqual(target.read_bytes(), b"previous")
            engines.expand_gzip(source, target, 500)
            self.assertEqual(target.read_bytes(), b"large" * 100)

    def test_manifest_hashes_real_files_and_uses_runtime_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for tool in ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"):
                (output / (tool + ".exe")).write_bytes(b"abc")
            engines.write_manifest(output, "windows", "x86_64", {tool: "1.0" for tool in
                ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe")})
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual(manifest["schemaVersion"], 1)
            self.assertEqual((manifest["platform"], manifest["arch"]), ("windows", "x86_64"))
            self.assertEqual(manifest["tools"]["gallery-dl"], {
                "file": "gallery-dl.exe", "version": "1.0",
                "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"})
            self.assertEqual(len(manifest["tools"]), 4)

    def test_only_https_downloads_are_accepted_even_with_cached_file(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "HTTPS"):
                engines.download("http://example.com/tool", "0" * 64, Path(directory), 100)

    def test_source_archives_cannot_escape_expected_source_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, kind in [("../outside", tarfile.REGTYPE), ("source/link", tarfile.SYMTYPE)]:
                with self.subTest(name=name):
                    archive = root / "source.tar.gz"
                    with tarfile.open(archive, "w:gz") as output:
                        member = tarfile.TarInfo(name)
                        member.type = kind
                        member.linkname = "../../outside"
                        output.addfile(member)
                    with self.assertRaises(ValueError):
                        engines.extract_source(archive, root / "build", "source")
                    self.assertFalse((root / "outside").exists())

    def test_regular_source_archives_unpack_and_expansion_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                member = tarfile.TarInfo("source/configure")
                member.size = 4
                output.addfile(member, io.BytesIO(b"test"))
            with self.assertRaisesRegex(ValueError, "size"):
                engines.extract_source(archive, root / "build", "source", max_size=3)
            source = engines.extract_source(archive, root / "build", "source")
            self.assertEqual((source / "configure").read_bytes(), b"test")

    def test_signing_limits_library_validation_exception_to_vendor_yt_dlp(self):
        binary = Path("yt-dlp")
        entitlements = Path("vendor.plist")
        real = engines.macos_signing_args("Developer ID Application: Example", binary, entitlements)
        self.assertIn("--timestamp", real)
        self.assertIn("runtime", real)
        self.assertEqual(real[real.index("--sign") + 1], "Developer ID Application: Example")
        self.assertIn("--entitlements", real)
        gallery = engines.macos_signing_args("Developer ID Application: Example", Path("gallery-dl"), entitlements)
        self.assertNotIn("--entitlements", gallery)
        adhoc = engines.macos_signing_args("-", binary, entitlements)
        self.assertNotIn("--timestamp", adhoc)
        self.assertNotIn("--options", adhoc)
        self.assertNotIn("--entitlements", adhoc)

    def test_gallery_entrypoint_flushes_completed_path_before_process_finishes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "entry.py").write_text(engines.GALLERY_ENTRYPOINT)
            (root / "gallery_dl.py").write_text("def main():\n    print('completed.jpg')\n    input()\n")
            process = subprocess.Popen([sys.executable, str(root / "entry.py")], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            with ThreadPoolExecutor(max_workers=1) as executor:
                try:
                    line = executor.submit(process.stdout.readline).result(timeout=5)
                    self.assertEqual(line, "completed.jpg\n")
                    self.assertIsNone(process.poll())
                finally:
                    process.kill()
                    process.communicate()

    def test_arm_build_cache_changes_when_link_configuration_changes(self):
        before = {"ffmpeg-9.0.2": ["--disable-autodetect"]}
        after = {"ffmpeg-9.0.2": ["--disable-autodetect", "--enable-securetransport"]}
        self.assertNotEqual(engines.arm_cache_key(before), engines.arm_cache_key(after))
        self.assertEqual(engines.arm_cache_key(after), engines.arm_cache_key(dict(after)))

    def test_arm_build_keeps_https_and_png_with_autodetection_disabled(self):
        options = engines.ARM_BUILD_OPTIONS["ffmpeg-9.0.2"]
        self.assertIn("--disable-autodetect", options)
        self.assertIn("--enable-securetransport", options)
        self.assertIn("--enable-zlib", options)
        self.assertIn("--enable-libmp3lame", options)
        self.assertIn("--enable-libopus", options)
        for options in engines.ARM_BUILD_OPTIONS.values():
            self.assertIn("--disable-shared", options)
            self.assertIn("--enable-static", options)


if __name__ == "__main__":
    unittest.main()
