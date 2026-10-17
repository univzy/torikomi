"""Offline integration check: python scripts/test_gallery_engine.py --gallery-dl PATH."""

import argparse
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest


ENGINE = os.environ.get("TORIKOMI_GALLERY_DL") or shutil.which("gallery-dl")
EXTRACTOR = r'''
from gallery_dl.extractor.common import Extractor, Message

class TorikomiAlbumExtractor(Extractor):
    category = "torikomi_test"
    subcategory = "album"
    pattern = r"https?://127\.0\.0\.1:\d+/album$"
    filename_fmt = "{filename}.{extension}"
    directory_fmt = ()

    def items(self):
        yield Message.Directory, "", {"title": "Local album"}
        for name, extension in [("same", "jpg"), ("video", "mp4"),
                                ("same", "jpg"), ("third", "avif")]:
            yield Message.Url, self.url.rsplit("/", 1)[0] + "/" + name + "." + extension, {
                "filename": "../" + name, "extension": extension,
            }
'''


class GalleryEngineTest(unittest.TestCase):
    def test_unsupported_url_preserves_native_failure_status(self):
        if not ENGINE:
            self.skipTest("set TORIKOMI_GALLERY_DL or use --gallery-dl")
        result=subprocess.run([str(ENGINE),"--config-ignore","--no-input","--no-colors","--dump-json","--","torikomi-invalid://offline-fixture"],capture_output=True,text=True,encoding="utf-8",timeout=30)
        self.assertNotEqual(result.returncode,0)
        self.assertIn("Unsupported URL",result.stderr)

    def test_selected_album_downloads_all_selected_photos_and_reports_safe_paths(self):
        if not ENGINE:
            self.skipTest("set TORIKOMI_GALLERY_DL or use --gallery-dl for the engine integration test")
        requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                requests.append(self.path)
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.end_headers()
                self.wfile.write(b"local-photo-fixture")

        with tempfile.TemporaryDirectory(prefix="torikomi-gallery-test-") as temporary:
            root = Path(temporary)
            extractors = root / "extractors"
            extractors.mkdir()
            (extractors / "torikomi_album.py").write_text(EXTRACTOR, encoding="utf-8")
            destination = root / "download folder"
            destination.mkdir()
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            with server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    source = f"http://127.0.0.1:{server.server_port}/album"
                    base = [str(ENGINE), "--config-ignore", "--no-input", "--no-colors",
                            "--quiet", "--http-timeout", "30", "--retries", "3",
                            "-o", "output.mode=null", "--extractors", str(extractors)]

                    def run(arguments):
                        result = subprocess.run(base + arguments + ["--", source],
                                                capture_output=True, text=True,
                                                encoding="utf-8", timeout=30)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        return result.stdout

                    messages = json.loads(run(["--dump-json"]))
                    files = [message for message in messages if message[0] == 3]
                    self.assertEqual([message[2]["extension"] for message in files],
                                     ["jpg", "mp4", "jpg", "avif"])
                    self.assertEqual(requests, [], "inspection must not download media")
                    output = run([
                        # Absolute album items 1,3 map to ranks 1,2 after the photo filter.
                        "--range", "1,2", "--directory", str(destination),
                        "--filename", "fixture-{filename|id|num!s:.100}.{extension}",
                        "--restrict-filenames", "windows", "-o", "extractor.skip=enumerate",
                        "-o", "extractor.children=false", "--filter",
                        "(extension or '').lower() in ('jpg','jpeg','png','webp','gif','avif','heic','heif','jxl','bmp','tif','tiff')",
                        "--Print", "after:{_path!j}",
                    ])
                    paths = [Path(json.loads(line)) for line in output.splitlines() if line]
                    self.assertEqual(len(paths), 2, output)
                    self.assertEqual(len(set(paths)), 2, "duplicate filenames must preserve both photos")
                    self.assertEqual(requests, ["/same.jpg", "/same.jpg"])
                    self.assertEqual(set(destination.iterdir()), set(paths))
                    for path in paths:
                        self.assertTrue(path.resolve().is_relative_to(destination.resolve()))
                        self.assertEqual(path.suffix, ".jpg")
                        self.assertEqual(path.read_bytes(), b"local-photo-fixture")
                finally:
                    server.shutdown()
                    thread.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gallery-dl", help="Path to the prepared gallery-dl executable")
    args, remaining = parser.parse_known_args()
    if args.gallery_dl:
        ENGINE = str(Path(args.gallery_dl).resolve())
    unittest.main(argv=[__file__] + remaining)
