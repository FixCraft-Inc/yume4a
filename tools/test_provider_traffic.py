"""Offline fixture policy checks. Native packet carriage needs a separate live gate."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from provider_operator import Operator, validate_traffic_address


class TrafficPolicyChecks(unittest.TestCase):
    @staticmethod
    def native_command(binary: Path, *arguments: str) -> subprocess.CompletedProcess:
        """Supply only native setup's files; never claim to emulate its validation."""
        if arguments[0] == "init":
            output = Path(arguments[arguments.index("--output") + 1])
            server = output / "server"
            (server / "credentials").mkdir(parents=True)
            (server / "credentials/authorized-keys.json").write_text('{"keys":[{"name":"control"}]}')
            (server / "yumed.json").write_text(json.dumps({"endpoint": {}, "adapters": [
                {"type": "tcp"}, {"type": "udp"}]}))
        return subprocess.CompletedProcess([str(binary), *arguments], 0, b"", b"")

    def open_operator(self, root: Path, address: str | None = None, dns_address: str | None = None) -> Operator:
        return Operator(root, Path("/unused/yume"), Path("/unused/yumed"),
                        Path("/unused/yume-setup"), "edge.example", 4433,
                        traffic_address=address, traffic_dns_address=dns_address)

    @staticmethod
    def configuration(root: Path) -> Path:
        return root / "initial/server/yumed.json"

    def test_address_is_one_canonical_nonloopback_unicast_ipv4(self) -> None:
        for value in ("10.23.4.5", "192.0.2.42", "198.51.100.10", "203.0.113.7"):
            with self.subTest(value=value):
                self.assertEqual(validate_traffic_address(value), value)
        for value in ("", "localhost", "edge.example", "127.0.0.1", "127.4.5.6", "0.0.0.0",
                      "0.0.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "169.254.1.1",
                      "192.0.2.42/32", "192.0.2.0/24", "192.000.2.42", " 192.0.2.42",
                      "192.0.2.42\n", "::1", "::ffff:192.0.2.42", 3221226026, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_traffic_address(value)

    def test_default_policy_stays_loopback_only_and_restarts_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch("provider_operator.command", self.native_command), \
                patch("provider_operator.Supervisor") as supervisor:
            root = Path(directory)
            self.open_operator(root).close()
            original = self.configuration(root).read_bytes()
            for adapter in json.loads(original)["adapters"]:
                self.assertEqual(adapter["destinations"], {"public": False, "networks": ["127.0.0.0/8"]})
            self.open_operator(root).close()
            self.assertEqual(self.configuration(root).read_bytes(), original)
            self.assertEqual(supervisor.call_count, 2)

    def test_selected_address_adds_only_exact_host_and_restarts_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch("provider_operator.command", self.native_command), \
                patch("provider_operator.Supervisor") as supervisor:
            root = Path(directory)
            self.open_operator(root, "192.0.2.42").close()
            original = self.configuration(root).read_bytes()
            for adapter in json.loads(original)["adapters"]:
                self.assertEqual(adapter["destinations"],
                                 {"public": False, "networks": ["127.0.0.0/8", "192.0.2.42/32"]})
            self.open_operator(root, "192.0.2.42").close()
            self.assertEqual(self.configuration(root).read_bytes(), original)
            self.assertEqual(supervisor.call_count, 2)

    def test_dns_address_requires_traffic_address_and_validates_before_mutation(self) -> None:
        for traffic, resolver in ((None, "9.9.9.9"), ("192.0.2.42", "resolver.example"),
                                  ("192.0.2.42", "127.0.0.1"), ("192.0.2.42", "9.9.9.0/24")):
            with self.subTest(traffic=traffic, resolver=resolver), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaises(ValueError):
                    self.open_operator(root, traffic, resolver)
                self.assertEqual(list(root.iterdir()), [])

    def test_dns_policy_is_exact_deduplicated_and_stable_on_restart(self) -> None:
        for resolver, networks in (("9.9.9.9", ["127.0.0.0/8", "192.0.2.42/32", "9.9.9.9/32"]),
                                   ("192.0.2.42", ["127.0.0.0/8", "192.0.2.42/32"])):
            with self.subTest(resolver=resolver), tempfile.TemporaryDirectory() as directory, \
                    patch("provider_operator.command", self.native_command), patch("provider_operator.Supervisor"):
                root = Path(directory)
                self.open_operator(root, "192.0.2.42", resolver).close()
                original = self.configuration(root).read_bytes()
                for adapter in json.loads(original)["adapters"]:
                    self.assertEqual(adapter["destinations"], {"public": False, "networks": networks})
                self.open_operator(root, "192.0.2.42", resolver).close()
                self.assertEqual(self.configuration(root).read_bytes(), original)

    def test_restart_refuses_added_removed_or_changed_dns_address_before_daemon_start(self) -> None:
        for previous, replacement in ((None, "9.9.9.9"), ("9.9.9.9", None), ("9.9.9.9", "1.1.1.1")):
            with self.subTest(previous=previous, replacement=replacement), \
                    tempfile.TemporaryDirectory() as directory, \
                    patch("provider_operator.command", self.native_command), patch("provider_operator.Supervisor"):
                root = Path(directory)
                self.open_operator(root, "192.0.2.42", previous).close()
                original = self.configuration(root).read_bytes()
                with patch("provider_operator.Supervisor") as supervisor, \
                        patch("provider_operator.command") as command, \
                        self.assertRaisesRegex(ValueError, "traffic destination policy changed"):
                    self.open_operator(root, "192.0.2.42", replacement)
                supervisor.assert_not_called()
                command.assert_not_called()
                self.assertEqual(self.configuration(root).read_bytes(), original)

    def test_restart_refuses_added_removed_or_changed_address_before_daemon_start(self) -> None:
        for previous, replacement in ((None, "192.0.2.42"), ("192.0.2.42", None),
                                      ("192.0.2.42", "192.0.2.43")):
            with self.subTest(previous=previous, replacement=replacement), \
                    tempfile.TemporaryDirectory() as directory, \
                    patch("provider_operator.command", self.native_command), patch("provider_operator.Supervisor"):
                root = Path(directory)
                self.open_operator(root, previous).close()
                original = self.configuration(root).read_bytes()
                with patch("provider_operator.Supervisor") as supervisor, \
                        patch("provider_operator.command") as command, \
                        self.assertRaisesRegex(ValueError, "traffic destination policy changed"):
                    self.open_operator(root, replacement)
                supervisor.assert_not_called()
                command.assert_not_called()
                self.assertEqual(self.configuration(root).read_bytes(), original)

    def test_restart_refuses_broader_native_policy_without_rewriting_it(self) -> None:
        for policy in ({"public": True, "networks": ["127.0.0.0/8", "192.0.2.42/32"]},
                       {"public": False, "networks": ["127.0.0.0/8", "192.0.2.0/24"]}):
            with self.subTest(policy=policy), tempfile.TemporaryDirectory() as directory, \
                    patch("provider_operator.command", self.native_command), patch("provider_operator.Supervisor"):
                root = Path(directory)
                self.open_operator(root, "192.0.2.42").close()
                settings = json.loads(self.configuration(root).read_bytes())
                settings["adapters"][0]["destinations"] = policy
                self.configuration(root).write_text(json.dumps(settings))
                changed = self.configuration(root).read_bytes()
                with patch("provider_operator.Supervisor") as supervisor, \
                        self.assertRaisesRegex(ValueError, "traffic destination policy changed"):
                    self.open_operator(root, "192.0.2.42")
                supervisor.assert_not_called()
                self.assertEqual(self.configuration(root).read_bytes(), changed)


if __name__ == "__main__":
    unittest.main()
