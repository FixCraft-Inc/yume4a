#!/usr/bin/env python3
"""Host-only regression for arguments crossing adb's remote POSIX shell."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest


class DeviceShellTest(unittest.TestCase):
    def test_arguments_remain_literal(self) -> None:
        harness = Path(__file__).with_name("android-agent.sh")
        with tempfile.TemporaryDirectory(prefix="yume-adb-quote-") as directory:
            root = Path(directory)
            helper = root / "arguments.py"
            helper.write_text("import json, sys\nprint(json.dumps(sys.argv[1:]))\n")
            fake_adb = root / "adb.sh"
            fake_adb.write_text('test "$1" = shell || exit 2\nexec /bin/sh -c "$2"\n')
            marker = root / "must-not-exist"
            arguments = [
                "", "a b", "a'b", "line\nbreak", "\\", '"',
                f"https://example.invalid/?a=1&b=';touch {marker};#",
                f"$(touch {marker})", f"`touch {marker}`",
            ]
            result = subprocess.run(
                ["bash", "-c", 'source "$1"; ADB=(bash "$2"); shift 2; device_shell "$@"',
                 "test", str(harness), str(fake_adb), "python3", str(helper), *arguments],
                capture_output=True, text=True, timeout=10, check=True,
            )
            self.assertEqual(json.loads(result.stdout), arguments)
            self.assertFalse(marker.exists())

    def test_numeric_inputs_refuse_shell_expressions(self) -> None:
        harness = Path(__file__).with_name("android-agent.sh")
        for value in ["1+1", "0010", "-1", "x[0]", "1; true", "99999999999999999999"]:
            result = subprocess.run(
                ["bash", "-c", 'source "$1"; network_bench https://example.invalid/ 1024 "$2"',
                 "test", str(harness), value],
                capture_output=True, text=True, timeout=10,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("invalid benchmark timeout", result.stderr)

    def test_package_override_refuses_shell_syntax(self) -> None:
        harness = Path(__file__).with_name("android-agent.sh")
        for value in ["com.example;id", "com.example agent", "com", "com.$(id)", "1com.example"]:
            result = subprocess.run(
                ["bash", "-c", 'source "$1"', "test", str(harness)],
                capture_output=True, text=True, timeout=10,
                env={"PATH": "/usr/bin:/bin", "YUME_ANDROID_AGENT_PACKAGE": value},
            )
            self.assertNotEqual(result.returncode, 0, value)
            self.assertIn("invalid YUME_ANDROID_AGENT_PACKAGE", result.stderr)


if __name__ == "__main__":
    unittest.main()
