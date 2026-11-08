#!/usr/bin/env python3
"""Exercise the archive boundary, including Y1's APK with no notice entries."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
import zipfile

# Host-only checks must not create Git-visible output beside their sources.
sys.dont_write_bytecode = True
from check_apk_notices import verify


class ApkNoticesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="yume-notice-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.expected = self.root / "generated"
        notices = self.expected / "notices"
        notices.mkdir(parents=True)
        self.text = b"Copyright Example\nFull permission and warranty terms.\n"
        (notices / "LICENSE.txt").write_bytes(self.text)
        (notices / "manifest.json").write_text(json.dumps({
            "schema": 1, "artifacts": [{"coordinate": "example:library:1"}],
            "sourceComponents": [{"name": "example-native"}],
            "files": {"LICENSE.txt": {"sha256": hashlib.sha256(self.text).hexdigest()}},
        }) + "\n")
        self.apk = self.root / "candidate.apk"

    def write_apk(self, text: bytes | None, *, duplicate: bool = False, extra: bool = False) -> None:
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", b"synthetic fixture")
            if text is not None:
                archive.writestr("assets/notices/manifest.json",
                                 (self.expected / "notices/manifest.json").read_bytes())
                archive.writestr("assets/notices/LICENSE.txt", text)
                if duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr("assets/notices/LICENSE.txt", text)
                if extra:
                    archive.writestr("assets/notices/stale.txt", b"obsolete dependency")

    def test_complete_text_passes(self) -> None:
        self.write_apk(self.text)
        self.assertEqual(verify(self.apk, self.expected)["notice_entries"], 2)

    def test_y1_omission_fails(self) -> None:
        self.write_apk(None)
        with self.assertRaisesRegex(ValueError, "Notice inventory differs: missing="):
            verify(self.apk, self.expected)

    def test_truncated_text_fails(self) -> None:
        self.write_apk(self.text[:-1])
        with self.assertRaisesRegex(ValueError, "Incomplete or changed notice"):
            verify(self.apk, self.expected)

    def test_duplicate_text_fails(self) -> None:
        self.write_apk(self.text, duplicate=True)
        with self.assertRaisesRegex(ValueError, "Duplicate notice entries"):
            verify(self.apk, self.expected)

    def test_stale_notice_fails(self) -> None:
        self.write_apk(self.text, extra=True)
        with self.assertRaisesRegex(ValueError, "unexpected="):
            verify(self.apk, self.expected)

    def test_damaged_generated_source_fails(self) -> None:
        self.write_apk(self.text)
        (self.expected / "notices/LICENSE.txt").write_bytes(b"SPDX label only")
        with self.assertRaisesRegex(ValueError, "Generated notice hash differs"):
            verify(self.apk, self.expected)


if __name__ == "__main__":
    unittest.main()
