#!/usr/bin/env python3
"""Publishable engine downloads and a small pinned first-launch manifest."""

import argparse
import json
from pathlib import Path
import re
import shutil
import tempfile

import engine_updates


def package(bundle, output, manifest_path, tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag):
        raise ValueError("Expected a versioned application release tag")
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bootstrap-", dir=output) as temporary:
        generated = engine_updates.package(bundle, temporary, {"revision": tag, "minAppVersion": "2.0.0"})
        target_name = generated["platform"] + "-" + generated["arch"]
        target = {"platform": generated["platform"], "arch": generated["arch"], "tools": {},
                  "notices": (Path(temporary) / generated["notices"]["asset"]).read_text(encoding="utf-8")}
        base = "https://github.com/univzy/torikomi/releases/download/" + tag + "/"
        for name, tool in generated["tools"].items():
            asset = f"torikomi-engine-{target_name}-{name}-{tool['compressedSha256'][:12]}.gz"
            shutil.copyfile(Path(temporary) / tool["asset"], output / asset)
            target["tools"][name] = {"file": tool["file"], "version": tool["version"],
                "url": base + asset, "format": "gzip", "sha256": tool["sha256"], "size": tool["size"],
                "downloadSha256": tool["compressedSha256"], "downloadSize": tool["compressedSize"]}
        notices_name = f"torikomi-engine-{target_name}-notices-{generated['notices']['sha256'][:12]}.json"
        shutil.copyfile(Path(temporary) / generated["notices"]["asset"], output / notices_name)
        result = {"schemaVersion": 1, "targets": [target]}
        manifest_path = Path(manifest_path)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parents[1] / "shared/desktop/src-tauri/bootstrap-engines.json")
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    result = package(args.bundle, args.output, args.manifest, args.tag)
    print(f"Prepared {len(result['targets'][0]['tools'])} separate engine downloads")


if __name__ == "__main__":
    main()
