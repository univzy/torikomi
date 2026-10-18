"""Run native engine compatibility checks before any feed publication."""

import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

TOOLS = ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe")


def execute(arguments):
    command = [str(argument) for argument in arguments]
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120).stdout
    except subprocess.CalledProcessError as error:
        details = "\n".join(value.strip() for value in (error.stdout, error.stderr) if value)
        raise RuntimeError(f"Engine check failed ({error.returncode}): {command!r}\n{details}") from error


def verify_bundle(bundle):
    bundle = Path(bundle).resolve()
    manifest = json.loads((bundle / "manifest.json").read_text())
    tools = {name: bundle / manifest["tools"][name]["file"] for name in TOOLS}
    for name, binary in tools.items():
        flag = "-version" if name in ("ffmpeg", "ffprobe") else "--version"
        first_line = execute([binary, flag]).splitlines()[0]
        version = manifest["tools"][name]["version"]
        if name in ("ffmpeg", "ffprobe"):
            if not first_line.startswith(f"{name} version {version} ") and not first_line.startswith(f"{name} version {version}-"):
                raise ValueError(f"Unexpected {name} version")
        elif first_line.strip() != version:
            raise ValueError(f"Unexpected {name} version")
        if manifest["platform"] == "windows":
            with binary.open("rb") as stream:
                if stream.read(2) != b"MZ":
                    raise ValueError("Invalid Windows executable")
                stream.seek(0x3C)
                stream.seek(struct.unpack("<I", stream.read(4))[0])
                if stream.read(4) != b"PE\x00\x00" or struct.unpack("<H", stream.read(2))[0] != 0x8664:
                    raise ValueError("Engine is not a native Windows x86_64 executable")
            if name in ("ffmpeg", "ffprobe") and os.environ.get("MSYS2_LOCATION"):
                inspector = Path(os.environ["MSYS2_LOCATION"]) / "mingw64/bin/objdump.exe"
                imports = execute([inspector, "-p", binary])
                allowed = {"kernel32.dll", "user32.dll", "advapi32.dll", "bcrypt.dll", "crypt32.dll", "ws2_32.dll", "msvcrt.dll", "secur32.dll", "shell32.dll", "ole32.dll", "gdi32.dll", "winmm.dll", "ntdll.dll", "shlwapi.dll", "oleaut32.dll", "comdlg32.dll", "psapi.dll", "avicap32.dll", "vfw32.dll", "dwmapi.dll", "strmiids.dll", "mfplat.dll", "mfuuid.dll", "normaliz.dll", "iphlpapi.dll", "ncrypt.dll", "userenv.dll", "ucrtbase.dll"}
                for line in imports.splitlines():
                    if "DLL Name:" in line:
                        dependency = line.split("DLL Name:", 1)[1].strip().lower()
                        if dependency not in allowed and not dependency.startswith(("api-ms-win-", "ext-ms-win-")):
                            raise ValueError(f"Unbundled engine dependency: {dependency}")
        else:
            architecture = "arm64" if manifest["arch"] == "aarch64" else "x86_64"
            execute(["lipo", binary, "-verify_arch", architecture])
            dependencies = execute(["otool", "-arch", architecture, "-L", binary])
            for line in dependencies.splitlines()[1:]:
                library = line.strip().split(" (", 1)[0]
                if not library.startswith(("/usr/lib/", "/System/Library/")):
                    raise ValueError(f"Unbundled engine dependency: {library}")
    execute([sys.executable, Path(__file__).with_name("test_gallery_engine.py"), "--gallery-dl", tools["gallery-dl"]])
    with tempfile.TemporaryDirectory(prefix="torikomi-engine-smoke-") as directory:
        root = Path(directory)
        ffmpeg, ffprobe = tools["ffmpeg"], tools["ffprobe"]
        execute([ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=5", "-t", "1", "-c:v", "mpeg4", root / "video.mp4"])
        execute([ffmpeg, "-nostdin", "-v", "error", "-i", root / "video.mp4", "-frames:v", "1", root / "frame.png"])
        if not (root / "frame.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("FFmpeg frame extraction failed")
        for codec, extension in [("libmp3lame", "mp3"), ("libopus", "opus"), ("aac", "m4a"), ("flac", "flac")]:
            audio = root / ("tone." + extension)
            execute([ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", "-c:a", codec,
                     "-metadata", "title=Engine smoke", "-metadata", "artist=Torikomi fixture", "-metadata", "album=Local album", "-metadata", "track=2", audio])
            metadata = json.loads(execute([ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", audio]))
            tags = {key.lower(): value for owner in [metadata.get("format", {}), *metadata.get("streams", [])] for key, value in owner.get("tags", {}).items()}
            if tags.get("artist") != "Torikomi fixture" or tags.get("album") != "Local album":
                raise ValueError(f"Audio metadata did not survive {extension} conversion")
        execute([ffmpeg, "-nostdin", "-v", "error", "-i", root / "tone.mp3", "-i", root / "frame.png", "-map", "0:a", "-map", "1:v", "-c", "copy", "-id3v2_version", "3", "-disposition:v", "attached_pic", root / "cover.mp3"])
        attached = json.loads(execute([ffprobe, "-v", "error", "-show_streams", "-of", "json", root / "cover.mp3"]))
        if not any(stream.get("disposition", {}).get("attached_pic") == 1 for stream in attached["streams"]):
            raise ValueError("Attached album artwork was not preserved")
        # yt-dlp must run standalone and retain format/metadata discovery without a Python runtime.
        execute([tools["yt-dlp"], "--ignore-config", "--list-extractors"])
    print("Native engine versions, architecture, dependencies, album extraction, frames, audio tags and cover artwork passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    verify_bundle(parser.parse_args().bundle)
