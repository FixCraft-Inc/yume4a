#!/usr/bin/env python3
"""The provider library's flags match their manifest, and a changed one is caught."""
from __future__ import annotations

from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_provider_flags as flags  # noqa: E402


class ProviderFlagsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.saved = {name: getattr(flags, name) for name in ("DRAWABLES", "MAP", "FLAGS", "MANIFEST", "LICENSE")}
        self.work = Path(tempfile.mkdtemp())
        library = self.work / "provider-library"
        for name in ("DRAWABLES", "MAP", "FLAGS"):
            source = self.saved[name]
            target = library / source.relative_to(flags.LIBRARY)
            target.parent.mkdir(parents=True, exist_ok=True)
            (shutil.copytree if source.is_dir() else shutil.copyfile)(source, target)
            setattr(flags, name, target)
        flags.MANIFEST = flags.FLAGS / "manifest.json"
        flags.LICENSE = flags.FLAGS / "LICENSE-flag-icons"

    def tearDown(self) -> None:
        for name, value in self.saved.items():
            setattr(flags, name, value)
        shutil.rmtree(self.work)

    def test_the_committed_flags_match_their_manifest(self) -> None:
        self.assertEqual([], flags.check())

    def test_a_changed_flag_is_caught(self) -> None:
        with (flags.DRAWABLES / "flag_jp.webp").open("ab") as image:
            image.write(b"\0")
        self.assertEqual(["changed drawable flag_jp.webp"], flags.check())

    def test_an_unlisted_flag_is_caught(self) -> None:
        shutil.copyfile(flags.DRAWABLES / "flag_jp.webp", flags.DRAWABLES / "flag_zz.webp")
        self.assertEqual(["unlisted drawable flag_zz.webp"], flags.check())

    def test_an_edited_map_is_caught(self) -> None:
        flags.MAP.write_text(flags.MAP.read_text().replace("flag_jp", "flag_us"))
        self.assertEqual(["ProviderFlags.kt differs from the manifest"], flags.check())


if __name__ == "__main__":
    unittest.main()
