#!/usr/bin/env python3
"""Bind every shared provider source/build file, without build outputs or Git state."""

import argparse
import hashlib
import json
from pathlib import Path
import stat


def manifest(root: Path) -> dict:
    root = root.resolve(strict=True)
    paths = [root / name for name in ("build.gradle.kts", "VERSION", "README.md")]
    paths += sorted(path for path in (root / "src").rglob("*") if path.is_file() or path.is_symlink())
    files = {}
    for path in paths:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode):
            raise ValueError("Provider input must be a regular file")
        data = path.read_bytes()
        files[path.relative_to(root).as_posix()] = {
            "mode": stat.S_IMODE(info.st_mode),
            "sha256": hashlib.sha256(data).hexdigest(),
            "size": len(data),
        }
    # This spelling is also implemented by the Gradle source-binding task.
    canonical = "".join(f"{path}\0{entry['mode']}\0{entry['sha256']}\n" for path, entry in sorted(files.items()))
    return {"schema": 1, "component": "com.fixcraft.yume:provider-library",
            "version": (root / "VERSION").read_text().strip(), "files": files,
            "source_sha256": hashlib.sha256(canonical.encode()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(manifest(args.root), indent=2))


if __name__ == "__main__":
    main()
