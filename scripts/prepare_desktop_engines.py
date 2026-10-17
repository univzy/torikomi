#!/usr/bin/env python3
"""Prepare verified, standalone desktop engines on the native Python 3.13 host."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import plistlib
import shutil
import ssl
import struct
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
import venv


ROOT = Path(__file__).resolve().parents[1]
FFMPEG_RELEASE = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/"
YT_RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/"
VERSIONS = {"yt-dlp": "2026.08.19", "gallery-dl": "1.32.16", "ffmpeg": "6.1.1", "ffprobe": "6.1.1"}
GALLERY_ENTRYPOINT = """import sys
from gallery_dl import main
if __name__ == '__main__':
    if sys.stdout is not None:
        sys.stdout.reconfigure(line_buffering=True, write_through=True)
    raise SystemExit(main())
"""
FFMPEG_ASSETS = {
    ("windows", "x86_64"): {
        "suffix": "win32-x64",
        "ffmpeg": "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77",
        "ffprobe": "f309e6223ad89d2fe54bccd420a7709b66fd27540674e92309578ed491a43c8d",
        "LICENSE": "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903",
        "README": "a636a7183c58006351acbaf35303c0ed85c6e1320fd4e80de453ba6157de6311",
    },
    ("macos", "x86_64"): {
        "suffix": "darwin-x64",
        "ffmpeg": "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106",
        "ffprobe": "d4da574d6e2e197bd259b47d69cf262df9e312af24ad960444f6d806d3d4c186",
        "LICENSE": "2e1d16c72fd74e12063776371da757322f8b77589386532f4fd8634bde7de1af",
        "README": "e88a0325f8e5b75210355e37341824f074d3cd82def2125be54c914b62848a36",
    },
}
ARM_SOURCES = {
    "ffmpeg-9.0.2": ("https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz", "8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"),
    "lame-3.100": ("https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz", "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"),
    "opus-1.6.1": ("https://ftp.osuosl.org/pub/xiph/releases/opus/opus-1.6.1.tar.gz", "6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1"),
}
ARM_BUILD_OPTIONS = {
    "lame-3.100": ["--disable-shared", "--enable-static", "--disable-frontend", "--disable-decoder"],
    "opus-1.6.1": ["--disable-shared", "--enable-static", "--disable-doc", "--disable-extra-programs"],
    "ffmpeg-9.0.2": ["--disable-shared", "--enable-static", "--disable-autodetect", "--disable-doc",
                     "--disable-debug", "--disable-ffplay", "--enable-securetransport", "--enable-zlib",
                     "--enable-libmp3lame", "--enable-libopus", "--pkg-config-flags=--static"],
}
NOTICES = {
    "yt-dlp.LICENSE": ("https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/LICENSE", "7e12e5df4bae12cb21581ba157ced20e1986a0508dd10d0e8a4ab9a4cf94e85c"),
    "yt-dlp.README": ("https://raw.githubusercontent.com/yt-dlp/yt-dlp/2026.08.19/README.md", "7ab6c7d23fb3dc84320c17b65838700b8c7e8d0a8d39a3528c268236f460cf69"),
    "ffmpeg.GPLv3": ("https://raw.githubusercontent.com/FFmpeg/FFmpeg/n6.1.1/COPYING.GPLv3", "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903"),
    "ffmpeg.GPLv2": ("https://raw.githubusercontent.com/FFmpeg/FFmpeg/n6.1.1/COPYING.GPLv2", "8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643"),
    "ffmpeg.LGPLv3": ("https://raw.githubusercontent.com/FFmpeg/FFmpeg/n6.1.1/COPYING.LGPLv3", "da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768"),
    "ffmpeg.LGPLv2.1": ("https://raw.githubusercontent.com/FFmpeg/FFmpeg/n6.1.1/COPYING.LGPLv2.1", "b634ab5640e258563c536e658cad87080553df6f34f62269a21d554844e58bfe"),
    "Python.LICENSE": ("https://raw.githubusercontent.com/python/cpython/v3.13.14/LICENSE", "78b12c3a81360b357002334f0e70ea0e92eebf7a9b358805c03c48484945f3bb"),
}


def native_target(system=None, machine=None):
    system = platform.system() if system is None else system
    machine = platform.machine() if machine is None else machine
    os_name = {"Windows": "windows", "Darwin": "macos"}.get(system)
    arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}.get(machine.lower())
    if (os_name, arch) not in {*FFMPEG_ASSETS, ("macos", "aarch64")}:
        raise ValueError(f"Unsupported desktop build host: {system}/{machine}")
    return os_name, arch


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_verified(stream, destination, expected_hash, max_size):
    temporary = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha256()
    size = 0
    try:
        with temporary.open("wb") as output:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                if size > max_size:
                    raise ValueError(f"Download exceeds size limit: {destination.name}")
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != expected_hash:
            raise ValueError(f"SHA-256 mismatch: {destination.name}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def download(url, expected_hash, cache, max_size=128 * 1024 * 1024, ssl_context=None):
    if urllib.parse.urlparse(url).scheme != "https":
        raise ValueError("Engine downloads require HTTPS")
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / (expected_hash + "-" + url.rsplit("/", 1)[-1])
    if destination.is_file() and destination.stat().st_size <= max_size and sha256(destination) == expected_hash:
        return destination
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Torikomi-engine-builder/1"})
    with urllib.request.urlopen(request, timeout=120, context=ssl_context) as response:
        if urllib.parse.urlparse(response.url).scheme != "https":
            raise ValueError("Engine download redirected away from HTTPS")
        length = response.headers.get("Content-Length")
        if length is not None and int(length) > max_size:
            raise ValueError("Download exceeds size limit")
        write_verified(response, destination, expected_hash, max_size)
    return destination


def expand_gzip(source, destination, max_size=256 * 1024 * 1024):
    temporary = destination.with_suffix(destination.suffix + ".part")
    size = 0
    try:
        with gzip.open(source, "rb") as compressed, temporary.open("wb") as output:
            while chunk := compressed.read(1024 * 1024):
                size += len(chunk)
                if size > max_size:
                    raise ValueError(f"Expanded engine exceeds size limit: {source.name}")
                output.write(chunk)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def extract_source(archive, destination, expected_root, max_size=512 * 1024 * 1024):
    with tarfile.open(archive, "r:*") as source:
        members = source.getmembers()
        if len(members) > 20000 or sum(member.size for member in members) > max_size:
            raise ValueError("Source archive exceeds size limit")
        for member in members:
            path = PurePosixPath(member.name)
            if (not path.parts or path.parts[0] != expected_root or ".." in path.parts
                    or "\\" in member.name or not (member.isfile() or member.isdir())):
                raise ValueError(f"Unsafe source archive member: {member.name}")
        source.extractall(destination, members=members, filter="data")
    return destination / expected_root


def arm_cache_key(options):
    configuration = {"sources": ARM_SOURCES, "options": options, "deploymentTarget": "11.0"}
    return hashlib.sha256(json.dumps(configuration, sort_keys=True).encode()).hexdigest()[:16]


def build_arm_ffmpeg(build, output, cache):
    for command in ("clang", "make", "pkg-config", "otool"):
        if not shutil.which(command):
            raise ValueError(f"macOS ARM engine builder requires {command}; install Xcode tools and pkg-config on the build host")
    python = build / "venv/bin/python"
    ca_file = subprocess.check_output([str(python), "-c", "import certifi; print(certifi.where())"], text=True).strip()
    context = ssl.create_default_context(cafile=ca_file)
    sources = {}
    for name, (url, digest) in ARM_SOURCES.items():
        archive = download(url, digest, cache, 32 * 1024 * 1024, ssl_context=context)
        destination = build / "sources" / name
        marker = destination / ".torikomi-source-sha256"
        if not marker.is_file() or marker.read_text() != digest:
            extract_source(archive, build / "sources", name)
            marker.write_text(digest)
        sources[name] = destination
    prefix = build / ("static-" + arm_cache_key(ARM_BUILD_OPTIONS))
    environment = dict(os.environ, MACOSX_DEPLOYMENT_TARGET="11.0",
                       CFLAGS="-O2 -mmacosx-version-min=11.0", LDFLAGS="-mmacosx-version-min=11.0",
                       PKG_CONFIG_LIBDIR=str(prefix / "lib/pkgconfig"), PKG_CONFIG_PATH="")
    jobs = str(min(os.cpu_count() or 2, 8))
    if not (prefix / "bin/ffmpeg").is_file() or not (prefix / "bin/ffprobe").is_file():
        for name, options in ARM_BUILD_OPTIONS.items():
            configure = ["./configure", "--prefix=" + str(prefix), *options]
            if name == "ffmpeg-9.0.2":
                configure.extend(["--extra-cflags=-I" + str(prefix / "include"),
                                  "--extra-ldflags=-L" + str(prefix / "lib")])
            subprocess.run(configure, cwd=sources[name], env=environment, check=True)
            subprocess.run(["make", "-j", jobs], cwd=sources[name], env=environment, check=True)
            subprocess.run(["make", "install"], cwd=sources[name], env=environment, check=True)
    for tool in ("ffmpeg", "ffprobe"):
        shutil.copyfile(prefix / "bin" / tool, output / tool)
    licenses = output / "licenses"
    for name, source in sources.items():
        target = licenses / name
        target.mkdir(parents=True, exist_ok=True)
        for pattern in ("COPYING*", "LICENSE*", "README*"):
            for notice in source.glob(pattern):
                if notice.is_file():
                    shutil.copyfile(notice, target / notice.name)
        (target / "source.json").write_text(json.dumps({"url": ARM_SOURCES[name][0], "sha256": ARM_SOURCES[name][1]}, indent=2) + "\n", encoding="utf-8")
    (licenses / "ffmpeg.BUILD.txt").write_text(
        "Native macOS ARM static build: FFmpeg 9.0.2, LAME 3.100, Opus 1.6.1.\n"
        "FFmpeg uses its default LGPL license; GPL and nonfree options are disabled.\n"
        "Corresponding source archive URLs and hashes are included beside each library's license.\n"
        "Build commands are in scripts/prepare_desktop_engines.py in the Torikomi source distribution.\n",
        encoding="utf-8")


def write_manifest(output, os_name, arch, versions):
    extension = ".exe" if os_name == "windows" else ""
    manifest = {"schemaVersion": 1, "platform": os_name, "arch": arch, "tools": {}}
    for tool, version in versions.items():
        filename = tool + extension
        manifest["tools"][tool] = {"file": filename, "sha256": sha256(output / filename), "version": version}
    temporary = output / "manifest.json.part"
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    temporary.replace(output / "manifest.json")


def build_gallery(build, output, os_name, requirements=None):
    environment = build / "venv"
    python = environment / ("Scripts/python.exe" if os_name == "windows" else "bin/python")
    if not python.is_file():
        venv.EnvBuilder(with_pip=True).create(environment)
    requirements = requirements or ROOT / "scripts/desktop-engine-requirements.txt"
    subprocess.run([str(python), "-m", "pip", "--isolated", "install", "--disable-pip-version-check",
                    "--index-url", "https://pypi.org/simple", "--only-binary=:all:", "--require-hashes",
                    "-r", str(requirements)], check=True)
    wrapper = build / "gallery_cli.py"
    if not wrapper.is_file() or wrapper.read_text(encoding="utf-8") != GALLERY_ENTRYPOINT:
        wrapper.write_text(GALLERY_ENTRYPOINT, encoding="utf-8")
    signing_identity = os.environ.get("APPLE_SIGNING_IDENTITY", "-")
    signing = ["--codesign-identity", signing_identity] if os_name == "macos" and signing_identity != "-" else []
    subprocess.run([str(python), "-m", "PyInstaller", "--noconfirm", "--onefile", "--console", *signing,
                    "--name", "gallery-dl", "--collect-all", "gallery_dl", "--collect-all", "certifi",
                    "--distpath", str(output), "--workpath", str(build / "pyinstaller"),
                    "--specpath", str(build), str(wrapper)], check=True)
    # Preserve the distribution license texts for gallery-dl and every bundled Python dependency.
    subprocess.run([str(python), "-c", """
import importlib.metadata, pathlib, shutil, sys
output = pathlib.Path(sys.argv[1])
for name in ['gallery-dl', 'requests', 'certifi', 'charset-normalizer', 'idna', 'urllib3', 'pyinstaller', 'setuptools']:
    distribution = importlib.metadata.distribution(name)
    folder = output / name
    folder.mkdir(parents=True, exist_ok=True)
    metadata = distribution.metadata
    (folder / 'README.txt').write_text(metadata.get_payload() or str(metadata), encoding='utf-8')
    for file in distribution.files or []:
        if any(word in file.name.lower() for word in ['license', 'copying', 'notice', 'authors']):
            source = distribution.locate_file(file)
            if source.is_file():
                shutil.copyfile(source, folder / file.name)
""", str(output / "licenses")], check=True)


def macos_signing_args(identity, binary, entitlements):
    arguments = ["codesign", "--force", "--sign", identity]
    if identity != "-":
        arguments.extend(["--options", "runtime", "--timestamp"])
        if binary.name == "yt-dlp":
            arguments.extend(["--entitlements", str(entitlements)])
    return [*arguments, str(binary)]


def prepare(os_name, arch, candidate_lock=None):
    cache = ROOT / "build/desktop-engine-cache"
    build = ROOT / f"build/desktop-engines/{os_name}-{arch}"
    output = ROOT / f"shared/desktop/src-tauri/resources/engines/{os_name}-{arch}"
    build.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    (output / "licenses").mkdir(exist_ok=True)
    lock = None
    requirements = ROOT / "scripts/desktop-engine-requirements.txt"
    if candidate_lock:
        import engine_updates
        lock = engine_updates.validate_lock(json.loads(Path(candidate_lock).read_text(encoding="utf-8")))
        if sha256(requirements) != lock["requirementsSha256"]:
            raise ValueError("Candidate build dependencies differ from the resolved lock")
        candidate_requirements = build / "candidate-requirements.txt"
        lines = requirements.read_text(encoding="utf-8").splitlines()
        lines = [f"gallery-dl @ {lock['gallery']['url']} --hash=sha256:{lock['gallery']['sha256']}" if line.startswith("gallery-dl==") else line for line in lines]
        candidate_requirements.write_text("\n".join(lines) + "\n", encoding="utf-8")
        requirements = candidate_requirements
    extension = ".exe" if os_name == "windows" else ""
    yt_asset, yt_hash = ("yt-dlp.exe", "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a") if os_name == "windows" else (
        "yt-dlp_macos", "0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202")
    yt_url = YT_RELEASE + yt_asset
    if lock:
        yt_url, yt_hash = lock["yt"][os_name]["url"], lock["yt"][os_name]["sha256"]
    shutil.copyfile(download(yt_url, yt_hash, cache), output / ("yt-dlp" + extension))
    build_gallery(build, output, os_name, requirements)
    versions = dict(lock["versions"] if lock else VERSIONS)
    if lock:
        import build_candidate_ffmpeg
        build_candidate_ffmpeg.build(build, output, cache, os_name, arch, lock)
    elif (os_name, arch) == ("macos", "aarch64"):
        build_arm_ffmpeg(build, output, cache)
        versions.update(ffmpeg="9.0.2", ffprobe="9.0.2")
    else:
        assets = FFMPEG_ASSETS[os_name, arch]
        for tool in ("ffmpeg", "ffprobe"):
            archive = download(FFMPEG_RELEASE + tool + "-" + assets["suffix"] + ".gz", assets[tool], cache)
            expand_gzip(archive, output / (tool + extension))
    licenses = output / "licenses"
    licenses.mkdir(exist_ok=True)
    for name, (url, digest) in NOTICES.items():
        if lock and (name.startswith("yt-dlp.") or name.startswith("ffmpeg.")):
            continue
        shutil.copyfile(download(url, digest, cache, 1024 * 1024), licenses / name)
    if lock:
        for name, notice in lock["notices"].items():
            if not name.startswith("yt-dlp.") or Path(name).name != name:
                raise ValueError("Invalid candidate license filename")
            shutil.copyfile(download(notice["url"], notice["sha256"], cache, 1024 * 1024), licenses / name)
        shutil.copyfile(requirements, licenses / "candidate-requirements.txt")
        shutil.copyfile(candidate_lock, licenses / "candidate-lock.json")
        shutil.copyfile(ROOT / "scripts/build_candidate_ffmpeg.py", licenses / "build_candidate_ffmpeg.py")
    shutil.copyfile(Path(__file__), licenses / "prepare_desktop_engines.py")
    shutil.copyfile(ROOT / "scripts/desktop-engine-requirements.txt", licenses / "desktop-engine-requirements.txt")
    if not lock and (os_name, arch) != ("macos", "aarch64"):
        for notice in ("LICENSE", "README"):
            url = FFMPEG_RELEASE + assets["suffix"] + "." + notice
            shutil.copyfile(download(url, assets[notice], cache, 1024 * 1024), licenses / ("ffmpeg." + notice))
    if os_name == "macos":
        identity = os.environ.get("APPLE_SIGNING_IDENTITY", "-")
        entitlements = build / "yt-dlp-entitlements.plist"
        # Vendor onefile binaries contain embedded libraries that cannot be re-signed after freezing.
        entitlements.write_bytes(plistlib.dumps({"com.apple.security.cs.disable-library-validation": True}))
        for tool in versions:
            binary = output / tool
            binary.chmod(0o755)
            subprocess.run(macos_signing_args(identity, binary, entitlements), check=True)
            subprocess.run(["codesign", "--verify", "--strict", str(binary)], check=True)
    for tool, version in versions.items():
        binary = output / (tool + extension)
        if os_name == "macos":
            binary.chmod(0o755)
        flag = "-version" if tool in ("ffmpeg", "ffprobe") else "--version"
        result = subprocess.run([str(binary), flag], check=True, capture_output=True, text=True, timeout=120)
        if version not in result.stdout.splitlines()[0]:
            raise ValueError(f"Unexpected {tool} version: {result.stdout[:200]}")
        print(result.stdout.splitlines()[0], flush=True)
        if os_name == "macos":
            native_arch = "arm64" if arch == "aarch64" else "x86_64"
            libraries = subprocess.run(["otool", "-arch", native_arch, "-L", str(binary)],
                                       check=True, capture_output=True, text=True).stdout
            for line in libraries.splitlines()[1:]:
                library = line.strip().split(" (", 1)[0]
                if not library.startswith(("/usr/lib/", "/System/Library/")):
                    raise ValueError(f"{tool} has a non-system runtime dependency: {library}")
    encoders = subprocess.run([str(output / ("ffmpeg" + extension)), "-encoders"], check=True,
                              capture_output=True, text=True, timeout=120).stdout
    for encoder in ("libmp3lame", "libopus", "aac", "flac", "pcm_s16le"):
        if encoder not in encoders:
            raise ValueError(f"Bundled FFmpeg is missing the required {encoder} audio encoder")
    protocols = subprocess.run([str(output / ("ffmpeg" + extension)), "-protocols"], check=True,
                               capture_output=True, text=True, timeout=120).stdout
    if "https" not in protocols.split():
        raise ValueError("Bundled FFmpeg is missing HTTPS support")
    # Smoke-check dynamic extractor discovery in the frozen executable, without making network requests.
    subprocess.run([str(output / ("gallery-dl" + extension)), "--list-extractors"], check=True,
                   stdout=subprocess.DEVNULL, timeout=120)
    metadata = {"sources": {
        "yt-dlp": "https://github.com/yt-dlp/yt-dlp/tree/2026.08.19",
        "gallery-dl": "https://pypi.org/project/gallery-dl/1.32.16/",
        "ffmpeg": (ARM_SOURCES["ffmpeg-9.0.2"][0] if (os_name, arch) == ("macos", "aarch64")
                   else "https://github.com/eugeneware/ffmpeg-static/releases/tag/b6.1.1"),
        "ffmpeg-source": "https://ffmpeg.org/releases/ffmpeg-" + versions["ffmpeg"] + ".tar.xz",
        "python": "https://www.python.org/downloads/release/python-31314/",
    }, "python": platform.python_version(), "buildRequirements": (ROOT / "scripts/desktop-engine-requirements.txt").read_text(encoding="utf-8"),
        "notice": "Bundled FFmpeg/FFprobe " + versions["ffmpeg"] + " are pinned compatibility builds. "
                  "Windows/macOS Intel use 6.1.1, macOS ARM uses a native 9.0.2 source build. "
                  "The vendor macOS Intel README describes a newer build; executable version checks and manifest.json are authoritative. "
                  "See licenses/ for build information and corresponding source. "
                  "The application can also use a newer manually configured FFmpeg installation."}
    if lock:
        metadata["sources"].update({"yt-dlp":f"https://github.com/yt-dlp/yt-dlp/tree/{versions['yt-dlp']}",
                                    "gallery-dl":lock["gallery"]["url"], "ffmpeg":lock["ffmpeg"]["url"],
                                    "ffmpeg-source":lock["ffmpeg"]["url"]})
        metadata["buildRequirements"] = requirements.read_text(encoding="utf-8")
        metadata["notice"] = "Stable engines verified by upstream hashes; FFmpeg source signature uses the pinned project release key. Corresponding sources, build commands and licenses are included."
    (output / "sources.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    write_manifest(output, os_name, arch, versions)
    print(f"Prepared desktop engines: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("windows", "macos"))
    parser.add_argument("--arch", choices=("x86_64", "aarch64"))
    parser.add_argument("--candidate-lock", type=Path, help="Verified stable lock produced by engine_updates.py resolve")
    args = parser.parse_args()
    os_name, arch = native_target()
    if (args.platform and args.platform != os_name) or (args.arch and args.arch != arch):
        parser.error("Standalone engines must be built on a host matching the target platform and architecture")
    if sys.version_info[:2] != (3, 13) or struct.calcsize("P") != 8:
        parser.error("Use native 64-bit Python 3.13 (setup-python: python-version '3.13')")
    prepare(os_name, arch, args.candidate_lock)


if __name__ == "__main__":
    main()
