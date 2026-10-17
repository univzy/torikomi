import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import urllib.request
import zipfile

ASSETS = Path(__file__).resolve().parents[2] / "main" / "assets"


class GalleryRuntimeTest(unittest.TestCase):
    def test_rejects_private_dns_results(self):
        spec = importlib.util.spec_from_file_location("gallery_entry", ASSETS / "gallery_entry.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for address in ["127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "fd00::1"]:
            with self.assertRaises(OSError):
                module.public_addresses([(None, None, None, None, (address, 443))])
        self.assertTrue(module.public_addresses([(None, None, None, None, ("8.8.8.8", 443))]))

    def test_locked_wheels_load_gallery_on_python(self):
        with tempfile.TemporaryDirectory(prefix="torikomi-gallery-test-") as directory:
            for package in json.loads((ASSETS / "gallery-packages.json").read_text()):
                with urllib.request.urlopen(package["url"], timeout=30) as response:
                    data = response.read(2 * 1024 * 1024 + 1)
                self.assertEqual(package["bytes"], len(data))
                self.assertEqual(package["sha256"], hashlib.sha256(data).hexdigest())
                with zipfile.ZipFile(io.BytesIO(data)) as wheel:
                    wheel.extractall(directory)
            result = subprocess.run([sys.executable, str(ASSETS / "gallery_entry.py"), "--version"],
                                    env={**os.environ, "PYTHONPATH": directory}, capture_output=True, text=True, timeout=30, check=True)
            self.assertEqual("1.32.16", result.stdout.strip())
            code = "from gallery_dl.extractor.common import Message; from gallery_dl.extractor import find; assert Message.Url == 3; assert type(find('https://www.tiktok.com/@example/photo/1234567890')).__name__ == 'TiktokPostExtractor'"
            subprocess.run([sys.executable, "-c", code], env={**os.environ, "PYTHONPATH": directory}, timeout=30, check=True)


if __name__ == "__main__":
    unittest.main()
