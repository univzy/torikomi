#!/usr/bin/env python3
"""Resolve stable upstream locks and package/validate Torikomi's desktop engine feed."""

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "univzy/torikomi"
FFMPEG_KEY = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
TOOLS = ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe")
TARGETS = {"windows-x86_64", "macos-x86_64", "macos-aarch64"}
LIMIT = 512 * 1024 * 1024
METADATA_LIMIT = 2 * 1024 * 1024


def stable_version(value):
    value = value.removeprefix("v")
    if len(value) > 64 or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", value):
        raise ValueError(f"Unsupported stable version: {value}")
    return value


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_url(raw, hosts):
    url = urllib.parse.urlparse(raw)
    if url.scheme != "https" or url.hostname not in hosts or url.port not in (None, 443) or url.username or url.password:
        raise ValueError("Untrusted upstream URL")


def fetch(url, limit=METADATA_LIMIT):
    parsed = urllib.parse.urlparse(url)
    checked_url(url, {"api.github.com", "github.com", "pypi.org", "files.pythonhosted.org", "ffmpeg.org", "raw.githubusercontent.com"})
    headers = {"User-Agent": "Torikomi-engine-builder/1"}
    if parsed.hostname == "api.github.com" and os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        checked_url(response.url, {"api.github.com", "github.com", "pypi.org", "files.pythonhosted.org", "ffmpeg.org", "raw.githubusercontent.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"})
        if int(response.headers.get("Content-Length", 0)) > limit:
            raise ValueError("Upstream response exceeds size limit")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Upstream response exceeds size limit")
    return data


def checksum(text, name):
    matches = [line.split()[0].lower() for line in text.splitlines() if len(line.split()) == 2 and line.split()[1].lstrip("*") == name]
    if len(matches) != 1 or not re.fullmatch("[a-f0-9]{64}", matches[0]):
        raise ValueError(f"Missing, invalid or duplicate upstream checksum: {name}")
    return matches[0]


def verify_ffmpeg(archive, signature, key):
    def gpg_path(path):
        value = Path(path).resolve().as_posix()
        executable = (shutil.which("gpg") or "").replace("\\", "/").lower()
        if os.name == "nt" and "/usr/bin/" in executable and len(value) > 2 and value[1] == ":":
            return "/" + value[0].lower() + value[2:]
        return value

    with tempfile.TemporaryDirectory(prefix="torikomi-gpg-") as directory:
        home = Path(directory)
        home.chmod(0o700)
        keyfile = home / "release-key.asc"
        sigfile = home / "release.asc"
        keyfile.write_bytes(key)
        sigfile.write_bytes(signature)
        arguments = ["gpg", "--batch", "--homedir", gpg_path(home)]
        subprocess.run([*arguments, "--import", gpg_path(keyfile)], check=True, capture_output=True)
        listing = subprocess.run([*arguments, "--with-colons", "--fingerprint"], check=True, capture_output=True, text=True).stdout
        fingerprints = [line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:")]
        if not fingerprints or fingerprints[0] != FFMPEG_KEY:
            raise ValueError("FFmpeg release signing key fingerprint changed")
        verified = subprocess.run([*arguments, "--status-fd", "1", "--verify", gpg_path(sigfile), gpg_path(archive)], check=True, capture_output=True, text=True)
        if not any(line.startswith("[GNUPG:] VALIDSIG " + FFMPEG_KEY + " ") for line in verified.stdout.splitlines()):
            raise ValueError("FFmpeg signature does not match the pinned release key")


def validate_lock(lock):
    versions = lock["versions"]
    if lock["schemaVersion"] != 1 or set(versions) != set(TOOLS) or versions["ffmpeg"] != versions["ffprobe"]:
        raise ValueError("Invalid engine lock schema")
    for value in [*versions.values(), lock["minAppVersion"]]:
        if stable_version(value) != value:
            raise ValueError("Lock versions must be normalized")
    if not re.fullmatch(r"engines-[A-Za-z0-9._-]+", lock["revision"]) or not re.fullmatch("[a-f0-9]{64}", lock["requirementsSha256"]):
        raise ValueError("Invalid engine lock revision")
    for host, asset in [("files.pythonhosted.org", lock["gallery"]), ("ffmpeg.org", lock["ffmpeg"]), *[("github.com", asset) for asset in lock["yt"].values()]]:
        checked_url(asset["url"], {host})
        if not re.fullmatch("[a-f0-9]{64}", asset["sha256"]):
            raise ValueError("Invalid upstream SHA256")
    if lock["ffmpeg"]["url"] != f"https://ffmpeg.org/releases/ffmpeg-{versions['ffmpeg']}.tar.xz" or lock["ffmpeg"]["signingKey"] != FFMPEG_KEY:
        raise ValueError("Untrusted FFmpeg source release")
    for platform, filename in [("windows", "yt-dlp.exe"), ("macos", "yt-dlp_macos")]:
        if lock["yt"][platform]["url"] != f"https://github.com/yt-dlp/yt-dlp/releases/download/{versions['yt-dlp']}/{filename}":
            raise ValueError("Untrusted yt-dlp release")
    if not urllib.parse.urlparse(lock["gallery"]["url"]).path.endswith(f"/gallery_dl-{versions['gallery-dl']}-py3-none-any.whl"):
        raise ValueError("The gallery-dl lock must use its stable platform-independent wheel")
    return lock


def resolve(output, force=False):
    yt = json.loads(fetch("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
    if yt.get("draft") or yt.get("prerelease"):
        raise ValueError("yt-dlp latest is not stable")
    yt_version = stable_version(yt["tag_name"])
    sums = fetch(f"https://github.com/yt-dlp/yt-dlp/releases/download/{yt_version}/SHA2-256SUMS").decode()
    gallery = json.loads(fetch("https://pypi.org/pypi/gallery-dl/json"))
    gallery_version = stable_version(gallery["info"]["version"])
    wheels = [asset for asset in gallery["urls"] if asset["filename"] == f"gallery_dl-{gallery_version}-py3-none-any.whl" and not asset["yanked"]]
    if len(wheels) != 1:
        raise ValueError("Stable gallery-dl wheel is missing")
    page = fetch("https://ffmpeg.org/download.html").decode()
    releases = re.findall(r'ffmpeg-([0-9]+(?:\.[0-9]+){1,3})\.tar\.xz', page)
    if not releases:
        raise ValueError("No stable FFmpeg source release found")
    ffmpeg_version = max(set(releases), key=lambda value: tuple(map(int, value.split("."))))
    ffmpeg_url = f"https://ffmpeg.org/releases/ffmpeg-{ffmpeg_version}.tar.xz"
    archive = fetch(ffmpeg_url, 64 * 1024 * 1024)
    signature = fetch(ffmpeg_url + ".asc", 128 * 1024)
    key = fetch("https://ffmpeg.org/ffmpeg-devel.asc", 128 * 1024)
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "ffmpeg.tar.xz"
        source.write_bytes(archive)
        verify_ffmpeg(source, signature, key)
    versions = {"yt-dlp": yt_version, "gallery-dl": gallery_version, "ffmpeg": ffmpeg_version, "ffprobe": ffmpeg_version}
    requirement_hash = digest(ROOT / "scripts/desktop-engine-requirements.txt")
    identity = hashlib.sha256(json.dumps([versions, requirement_hash], sort_keys=True).encode()).hexdigest()[:16]
    lock = {"schemaVersion": 1, "versions": versions, "requirementsSha256": requirement_hash,
            "minAppVersion": json.loads((ROOT / "shared/desktop/src-tauri/tauri.conf.json").read_text())["version"],
            "revision": f"engines-{datetime.now(timezone.utc):%Y%m%d}-" + ((os.environ.get("GITHUB_RUN_ID", "manual") + "-") if force else "") + identity,
            "gallery": {"url": wheels[0]["url"], "sha256": wheels[0]["digests"]["sha256"]},
            "ffmpeg": {"url": ffmpeg_url, "sha256": hashlib.sha256(archive).hexdigest(), "signingKey": FFMPEG_KEY},
            "yt": {platform: {"url": f"https://github.com/yt-dlp/yt-dlp/releases/download/{yt_version}/{name}", "sha256": checksum(sums, name)} for platform, name in [("windows", "yt-dlp.exe"), ("macos", "yt-dlp_macos")]},
            "notices": {}}
    for name in ["LICENSE", "README.md"]:
        url = f"https://raw.githubusercontent.com/yt-dlp/yt-dlp/{yt_version}/{name}"
        lock["notices"]["yt-dlp." + name] = {"url": url, "sha256": hashlib.sha256(fetch(url)).hexdigest()}
    validate_lock(lock)
    existing = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100"))
    published = next((release for release in existing if not release["draft"] and release["prerelease"] and release["tag_name"].startswith("engines-")), None)
    changed = force or published is None or not published["tag_name"].endswith(identity)
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"changed={str(changed).lower()}\nrevision={lock['revision']}\n")
    print(json.dumps({"changed": changed, "revision": lock["revision"], "versions": versions}))


def package(bundle, destination, lock):
    bundle, destination = Path(bundle), Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    native = json.loads((bundle / "manifest.json").read_text())
    target = f"{native['platform']}-{native['arch']}"
    if target not in TARGETS or set(native["tools"]) != set(TOOLS):
        raise ValueError("Unsupported or incomplete native engine bundle")
    sources = json.loads((bundle / "sources.json").read_text())["sources"]
    notices = {"sources": sources, "licenses": {}}
    for source in sorted((bundle / "licenses").rglob("*")):
        if source.is_symlink():
            raise ValueError("License symlinks are not permitted")
        if source.is_file():
            if source.stat().st_size > METADATA_LIMIT:
                raise ValueError("License exceeds size limit")
            notices["licenses"][str(source.relative_to(bundle / "licenses")).replace("\\", "/")] = source.read_text(encoding="utf-8", errors="replace")
    notices_data = json.dumps(notices, ensure_ascii=False, sort_keys=True).encode()
    if not notices["licenses"] or len(notices_data) > METADATA_LIMIT:
        raise ValueError("Missing or oversized engine notices")
    notice_name = f"{target}-notices.json"
    (destination / notice_name).write_bytes(notices_data)
    result = {"schemaVersion": 1, "platform": native["platform"], "arch": native["arch"], "revision": lock["revision"], "minAppVersion": lock["minAppVersion"],
              "minOsVersion": "10.0" if native["platform"] == "windows" else "11.0",
              "sources": sources, "notices": {"asset": notice_name, "sha256": hashlib.sha256(notices_data).hexdigest(), "size": len(notices_data)}, "tools": {}}
    for name in TOOLS:
        tool = native["tools"][name]
        stable_version(tool["version"])
        suffix = ".exe" if native["platform"] == "windows" else ""
        if tool["file"] != name + suffix:
            raise ValueError("Unexpected native tool filename")
        source = bundle / tool["file"]
        if source.is_symlink() or not 0 < source.stat().st_size <= LIMIT or digest(source) != tool["sha256"]:
            raise ValueError("Native executable verification failed")
        asset_name = f"{target}-{name}.gz"
        compressed = destination / asset_name
        with source.open("rb") as stream, compressed.open("wb") as output:
            with gzip.GzipFile(filename=tool["file"], fileobj=output, mode="wb", mtime=0) as archive:
                shutil.copyfileobj(stream, archive, 1024 * 1024)
        if compressed.stat().st_size > LIMIT:
            raise ValueError("Compressed executable exceeds size limit")
        result["tools"][name] = {**tool, "asset": asset_name, "size": source.stat().st_size, "compressedSize": compressed.stat().st_size, "compressedSha256": digest(compressed)}
    if result["tools"]["ffmpeg"]["version"] != result["tools"]["ffprobe"]["version"]:
        raise ValueError("FFmpeg and FFprobe versions differ")
    (destination / f"{target}-manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def validate_assets(directory, expected_targets=TARGETS):
    root = Path(directory)
    manifests = list(root.glob("*-manifest.json"))
    if {path.name.removesuffix("-manifest.json") for path in manifests} != set(expected_targets):
        raise ValueError("Feed does not contain every required target")
    revisions = set()
    for path in manifests:
        if path.stat().st_size > METADATA_LIMIT:
            raise ValueError("Feed manifest exceeds size limit")
        manifest = json.loads(path.read_text())
        revisions.add(manifest["revision"])
        if set(manifest["tools"]) != set(TOOLS) or manifest["tools"]["ffmpeg"]["version"] != manifest["tools"]["ffprobe"]["version"]:
            raise ValueError("Incomplete engine manifest")
        for tool in [*manifest["tools"].values(), manifest["notices"]]:
            name = tool["asset"]
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}", name) or ".." in name:
                raise ValueError("Unsafe feed asset path")
            asset = root / name
            expected_size = tool.get("compressedSize", tool["size"])
            if asset.is_symlink() or not 0 < expected_size <= LIMIT or asset.stat().st_size != expected_size or digest(asset) != tool.get("compressedSha256", tool["sha256"]):
                raise ValueError("Feed asset hash or size mismatch")
            if "compressedSize" in tool:
                if not 0 < tool["size"] <= LIMIT:
                    raise ValueError("Expanded executable exceeds size limit")
                digest_value, size = hashlib.sha256(), 0
                with gzip.open(asset, "rb") as stream:
                    while data := stream.read(1024 * 1024):
                        size += len(data)
                        if size > tool["size"]:
                            raise ValueError("Expanded executable exceeds declared size")
                        digest_value.update(data)
                if size != tool["size"] or digest_value.hexdigest() != tool["sha256"]:
                    raise ValueError("Expanded executable hash or size mismatch")
    if len(revisions) != 1:
        raise ValueError("Feed target revisions differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    resolver = commands.add_parser("resolve")
    resolver.add_argument("--output", required=True)
    resolver.add_argument("--force", action="store_true")
    packager = commands.add_parser("package")
    packager.add_argument("--bundle", type=Path, required=True)
    packager.add_argument("--output", type=Path, required=True)
    packager.add_argument("--lock", type=Path, required=True)
    validator = commands.add_parser("validate")
    validator.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.command == "resolve":
        resolve(args.output, args.force)
    elif args.command == "package":
        lock = validate_lock(json.loads(args.lock.read_text()))
        package(args.bundle, args.output, lock)
    else:
        validate_assets(args.directory)


if __name__ == "__main__":
    main()
