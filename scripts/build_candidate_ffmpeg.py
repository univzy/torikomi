"""Build a signed stable FFmpeg source release on native desktop CI runners."""

import json
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess

import engine_updates
import prepare_desktop_engines as engines


def shell_path(path, os_name):
    value = str(Path(path).resolve()).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if os_name == "windows" and len(value) > 2 and value[1] == ":" else value


def build(build_root, output, cache, os_name, arch, lock):
    engine_updates.validate_lock(lock)
    if os_name == "windows":
        msys = Path(os.environ.get("MSYS2_LOCATION", "C:/msys64"))
        shell = msys / "usr/bin/bash.exe"
        if not shell.is_file():
            raise ValueError("Candidate Windows builds require MSYS2 with native MinGW GCC, OpenSSL, zlib, make and pkgconf")
        environment = dict(os.environ, MSYSTEM="MINGW64", CHERE_INVOKING="1")
        environment["PATH"] = str(msys / "mingw64/bin") + os.pathsep + str(msys / "usr/bin") + os.pathsep + environment["PATH"]
    else:
        shell = Path("/bin/bash")
        environment = dict(os.environ, MACOSX_DEPLOYMENT_TARGET="11.0")
    sources = {**{name: asset for name, asset in engines.ARM_SOURCES.items() if not name.startswith("ffmpeg-")},
               "ffmpeg-" + lock["versions"]["ffmpeg"]: (lock["ffmpeg"]["url"], lock["ffmpeg"]["sha256"])}
    source_identity = hashlib.sha256(json.dumps([sources, os_name, arch, engines.sha256(Path(__file__)), engines.sha256(Path(engines.__file__))], sort_keys=True).encode()).hexdigest()[:16]
    workspace = build_root / ("candidate-ffmpeg-" + source_identity)
    prefix = workspace / "static"
    prefix.mkdir(parents=True, exist_ok=True)
    source_root = workspace / "sources"
    source_root.mkdir(exist_ok=True)
    configured = {}
    for name, (url, digest) in sources.items():
        archive = engines.download(url, digest, cache, 64 * 1024 * 1024)
        if name.startswith("ffmpeg-"):
            # Reverify on every native target so a lock alone cannot bypass the pinned signing key.
            signature = engine_updates.fetch(url + ".asc", 128 * 1024)
            key = engine_updates.fetch("https://ffmpeg.org/ffmpeg-devel.asc", 128 * 1024)
            engine_updates.verify_ffmpeg(archive, signature, key)
        destination = source_root / name
        if not destination.exists():
            engines.extract_source(archive, source_root, name)
        configured[name] = destination
    prefix_arg = shell_path(prefix, os_name)
    environment["PKG_CONFIG_PATH"] = prefix_arg + "/lib/pkgconfig"
    if os_name == "macos":
        environment.update(CFLAGS="-O2 -mmacosx-version-min=11.0", LDFLAGS="-mmacosx-version-min=11.0", PKG_CONFIG_LIBDIR=prefix_arg + "/lib/pkgconfig")

    def run(command, directory):
        script = "cd " + shlex.quote(shell_path(directory, os_name)) + " && " + shlex.join(command)
        subprocess.run([str(shell), "--login", "-c", script], env=environment, check=True)

    ffmpeg_name = "ffmpeg-" + lock["versions"]["ffmpeg"]
    options = {name: list(flags) for name, flags in engines.ARM_BUILD_OPTIONS.items() if not name.startswith("ffmpeg-")}
    options[ffmpeg_name] = ["--disable-shared", "--enable-static", "--disable-autodetect", "--disable-doc", "--disable-debug", "--disable-ffplay", "--enable-zlib",
                            "--enable-libmp3lame", "--enable-libopus", "--pkg-config-flags=--static", "--extra-cflags=-I" + prefix_arg + "/include",
                            "--extra-ldflags=-L" + prefix_arg + "/lib" + (" -static" if os_name == "windows" else "")]
    options[ffmpeg_name].extend(["--enable-openssl", "--enable-version3", "--target-os=mingw32", "--arch=x86_64"] if os_name == "windows" else ["--enable-securetransport"])
    extension = ".exe" if os_name == "windows" else ""
    if not (prefix / ("bin/ffmpeg" + extension)).is_file() or not (prefix / ("bin/ffprobe" + extension)).is_file():
        for name, flags in options.items():
            run(["./configure", "--prefix=" + prefix_arg, *flags], configured[name])
            run(["make", "-j", str(min(os.cpu_count() or 2, 8))], configured[name])
            run(["make", "install"], configured[name])
    for name in ["ffmpeg", "ffprobe"]:
        shutil.copyfile(prefix / ("bin/" + name + extension), output / (name + extension))
    licenses = output / "licenses"
    for name, source in configured.items():
        destination = licenses / name
        destination.mkdir(parents=True, exist_ok=True)
        for pattern in ["COPYING*", "LICENSE*", "README*"]:
            for notice in source.glob(pattern):
                if notice.is_file():
                    shutil.copyfile(notice, destination / notice.name)
        (destination / "source.json").write_text(json.dumps({"url": sources[name][0], "sha256": sources[name][1], "configure": options[name]}, indent=2) + "\n", encoding="utf-8")
    if os_name == "windows":
        for name in ["openssl", "zlib"]:
            source = msys / ("mingw64/share/licenses/" + name)
            if not source.is_dir():
                raise ValueError(f"Missing native dependency license: {name}")
            shutil.copytree(source, licenses / name, dirs_exist_ok=True)
        package_metadata = subprocess.check_output([str(shell), "--login", "-c", "pacman -Qi mingw-w64-x86_64-openssl mingw-w64-x86_64-zlib"], env=environment, text=True)
        package_versions = subprocess.check_output([str(shell), "--login", "-c", "pacman -Q mingw-w64-x86_64-openssl mingw-w64-x86_64-zlib"], env=environment, text=True)
        for line in package_versions.splitlines():
            package_name, package_version = line.split()
            version = engine_updates.stable_version(package_version.rsplit("-", 1)[0])
            name = package_name.removeprefix("mingw-w64-x86_64-")
            source_url = f"https://github.com/openssl/openssl/releases/download/openssl-{version}/openssl-{version}.tar.gz" if name == "openssl" else f"https://zlib.net/fossils/zlib-{version}.tar.gz"
            (licenses / name / "source.json").write_text(json.dumps({"url": source_url, "msys2Package": package_name, "packageVersion": package_version}, indent=2) + "\n", encoding="utf-8")
        (licenses / "MSYS2-runtime-sources.txt").write_text("Stable MinGW build dependencies and versions:\n" + package_metadata + "\nCorresponding package build/source recipes: https://github.com/msys2/MINGW-packages/tree/master/mingw-w64-openssl and https://github.com/msys2/MINGW-packages/tree/master/mingw-w64-zlib\n", encoding="utf-8")
    (licenses / "ffmpeg.BUILD.txt").write_text(f"Native {os_name}/{arch} static FFmpeg/FFprobe {lock['versions']['ffmpeg']} build.\nStable source release signature verified against {engine_updates.FFMPEG_KEY}.\nGPL and nonfree components disabled. LAME/Opus/zlib enabled. HTTPS uses " + ("OpenSSL 3 (LGPLv3 FFmpeg)" if os_name == "windows" else "system SecureTransport") + ".\nSource URLs, SHA256 and configure options accompany each source license. Build commands are included in build_candidate_ffmpeg.py.\n", encoding="utf-8")
