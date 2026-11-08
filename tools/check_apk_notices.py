#!/usr/bin/env python3
"""Compare packaged dependency notices with the variant's generated assets."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile


def expected_notices(directory: Path) -> dict[str, bytes]:
    root = directory / "notices"
    manifest_bytes = (root / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    if (manifest["schema"] != 1 or not manifest["artifacts"] or
            not manifest["sourceComponents"] or not manifest["files"]):
        raise ValueError("Incomplete generated notice inventory")
    result = {"assets/notices/manifest.json": manifest_bytes}
    for name, entry in manifest["files"].items():
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in (".", "..", "manifest.json"):
            raise ValueError(f"Invalid notice name: {name}")
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Missing regular notice source: {name}")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"Generated notice hash differs: {name}")
        result["assets/notices/" + name] = data
    return result


def verify(apk: Path, expected_directory: Path) -> dict:
    expected = expected_notices(expected_directory)
    with zipfile.ZipFile(apk) as archive:
        counts = Counter(name for name in archive.namelist()
                         if name.startswith("assets/notices/") and not name.endswith("/"))
        duplicates = sorted(name for name, count in counts.items() if count != 1)
        if duplicates:
            raise ValueError("Duplicate notice entries: " + ", ".join(duplicates))
        missing = sorted(expected.keys() - counts.keys())
        extra = sorted(counts.keys() - expected.keys())
        if missing or extra:
            raise ValueError(f"Notice inventory differs: missing={missing}, unexpected={extra}")
        for name, data in expected.items():
            entry = archive.getinfo(name)
            if entry.file_size != len(data) or archive.read(entry) != data:
                raise ValueError(f"Incomplete or changed notice: {name}")
    return {"apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "notice_entries": len(expected),
            "notice_sha256": {name: hashlib.sha256(data).hexdigest()
                              for name, data in expected.items()}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-dir", required=True, type=Path)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.apk, args.expected_dir)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Dependency notice check failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
