"""Publish a validated engine set through a draft in the pinned feed repository."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

import engine_updates


def publish(directory, revision):
    directory = Path(directory).resolve()
    if os.environ.get("GITHUB_REPOSITORY") != engine_updates.REPOSITORY:
        raise ValueError("Engine feed publishing is restricted to the pinned repository")
    engine_updates.validate_assets(directory)
    lock = engine_updates.validate_lock(json.loads((directory / "candidate-lock.json").read_text()))
    if lock["revision"] != revision:
        raise ValueError("Publication revision differs from resolved engine lock")
    for manifest in directory.glob("*-manifest.json"):
        if json.loads(manifest.read_text())["revision"] != revision:
            raise ValueError("Publication contains a different engine revision")

    def gh(arguments, **options):
        return subprocess.run(["gh", *arguments, "--repo", engine_updates.REPOSITORY], check=True, capture_output=True, text=True, **options)

    existing = subprocess.run(["gh", "release", "view", revision, "--repo", engine_updates.REPOSITORY, "--json", "isDraft,isPrerelease"], capture_output=True, text=True)
    if existing.returncode == 0:
        release = json.loads(existing.stdout)
        if not release["isDraft"] or not release["isPrerelease"]:
            raise ValueError("Published engine releases are immutable; resolve a new revision")
    else:
        notes = directory / "release-notes.md"
        notes.write_text("Stable desktop engines for Windows x86_64, macOS Intel and Apple Silicon.\n\n"
                         "This release is marked prerelease to keep engine packages separate from application installers. "
                         "All engines use stable upstream versions. Native compatibility tests completed before this draft was created. "
                         "Source references, licenses, SHA256 and exact sizes accompany each platform manifest.\n", encoding="utf-8")
        gh(["release", "create", revision, "--draft", "--prerelease", "--target", os.environ["GITHUB_SHA"], "--title", "Torikomi desktop engines " + revision.removeprefix("engines-"), "--notes-file", str(notes)])
    assets = sorted(path for path in directory.iterdir() if path.is_file() and path.suffix in (".json", ".gz"))
    gh(["release", "upload", revision, *[str(path) for path in assets], "--clobber"])
    with tempfile.TemporaryDirectory(prefix="torikomi-feed-upload-") as temporary:
        gh(["release", "download", revision, "--dir", temporary])
        engine_updates.validate_assets(temporary)
        for asset in assets:
            if engine_updates.digest(Path(temporary) / asset.name) != engine_updates.digest(asset):
                raise ValueError("Uploaded release asset differs from the verified candidate")
    gh(["release", "edit", revision, "--draft=false", "--prerelease", "--latest=false"])
    print("Published complete verified engine feed: " + revision)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    arguments = parser.parse_args()
    publish(arguments.directory, arguments.revision)
