import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_engine_compatibility as compatibility


class EngineCompatibilityTests(unittest.TestCase):
    def test_macos_input_precedes_variable_length_architecture_list(self):
        for target, architecture in [("aarch64", "arm64"), ("x86_64", "x86_64")]:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                bundle = Path(directory).resolve()
                manifest = {"platform": "macos", "arch": target, "tools": {
                    name: {"file": name, "version": "1.0"} for name in compatibility.TOOLS}}
                (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

                def inspect(arguments):
                    command = [str(argument) for argument in arguments]
                    if command == [str(bundle / "yt-dlp"), "--version"]:
                        return "1.0\n"
                    if command[0] == "lipo":
                        flag = command.index("-verify_arch")
                        if command[1:flag] != [str(bundle / "yt-dlp")] or command[flag + 1:] != [architecture]:
                            raise subprocess.CalledProcessError(1, command, stderr="Invalid lipo input or architecture list")
                        return ""
                    if command == ["otool", "-arch", architecture, "-L", str(bundle / "yt-dlp")]:
                        return "yt-dlp:\n\t/opt/homebrew/lib/unbundled.dylib (compatibility version 1.0.0)\n"
                    self.fail(f"Unexpected engine command: {command}")

                with patch.object(compatibility, "execute", side_effect=inspect), self.assertRaisesRegex(ValueError, "Unbundled engine dependency"):
                    compatibility.verify_bundle(bundle)

    def test_failed_native_command_reports_captured_diagnostics(self):
        with self.assertRaisesRegex(RuntimeError, "native fixture stderr") as failure:
            compatibility.execute([sys.executable, "-c", "import sys; print('native fixture stdout'); print('native fixture stderr', file=sys.stderr); sys.exit(7)"])
        self.assertIn("native fixture stdout", str(failure.exception))
        self.assertIn("7", str(failure.exception))
        self.assertIsInstance(failure.exception.__cause__, subprocess.CalledProcessError)

    def test_successful_native_command_returns_stdout(self):
        self.assertEqual(compatibility.execute([sys.executable, "-c", "print('native fixture')"]), "native fixture\n")


if __name__ == "__main__":
    unittest.main()
