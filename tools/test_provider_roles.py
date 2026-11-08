"""Offline role binding controls; native traffic is a separate build-host gate."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import closing, contextmanager
from pathlib import Path
import sqlite3
import tempfile
import time
from unittest.mock import Mock, patch
import unittest

import provider_conformance as contract
from provider_operator import Operator, encoded
from reference_provider import Provider
import test_provider_traffic


class RoleChecks(unittest.TestCase):
    @staticmethod
    def open_operator(root: Path, role: str = "vpn") -> Operator:
        return Operator(root, Path("/unused/yume"), Path("/unused/yumed"),
                        Path("/unused/yume-setup"), "edge.example", 4433, role=role)

    @contextmanager
    def provider(self, role: str) -> Iterator[Provider]:
        with tempfile.TemporaryDirectory() as directory, \
                patch("provider_operator.command", test_provider_traffic.TrafficPolicyChecks.native_command), \
                patch("provider_operator.Supervisor"):
            operator = self.open_operator(Path(directory), role)
            try:
                (operator.server / "credentials/server-composite.pub.pem").write_bytes(b"fixture-public-identity")
                yield Provider(operator, "https://provider.example", None, None)
            finally:
                operator.close()

    def test_default_and_explicit_roles_persist_and_restart_unchanged(self) -> None:
        for role in ("vpn", "relay"):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory, \
                    patch("provider_operator.command", test_provider_traffic.TrafficPolicyChecks.native_command), \
                    patch("provider_operator.Supervisor"):
                root = Path(directory)
                operator = self.open_operator(root) if role == "vpn" else self.open_operator(root, role)
                self.assertEqual(operator.role, role)
                self.assertEqual(operator.database.execute("SELECT value FROM meta WHERE key='role'").fetchone()[0], role)
                operator.close()
                original = (root / "initial/server/yumed.json").read_bytes()
                operator = self.open_operator(root, role)
                self.assertEqual(operator.role, role)
                self.assertEqual(operator.database.execute("SELECT value FROM meta WHERE key='role'").fetchone()[0], role)
                operator.close()
                self.assertEqual((root / "initial/server/yumed.json").read_bytes(), original)

    def test_invalid_role_refuses_before_mutation(self) -> None:
        for role in ("", "VPN", "Relay", "relay ", "calling", None):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaises(contract.Refusal) as refused:
                    self.open_operator(root, role)
                self.assertEqual(refused.exception.code, "unsupported")
                self.assertEqual(list(root.iterdir()), [])

    def test_changed_missing_or_corrupt_role_refuses_before_native_start(self) -> None:
        for role in ("vpn", "relay"):
            for damage in ("changed", "missing", "corrupt"):
                with self.subTest(role=role, damage=damage), tempfile.TemporaryDirectory() as directory, \
                        patch("provider_operator.command", test_provider_traffic.TrafficPolicyChecks.native_command), \
                        patch("provider_operator.Supervisor"):
                    root = Path(directory)
                    self.open_operator(root, role).close()
                    if damage != "changed":
                        with closing(sqlite3.connect(root / "journal.sqlite")) as database, database:
                            if damage == "missing":
                                database.execute("DELETE FROM meta WHERE key='role'")
                            else:
                                database.execute("UPDATE meta SET value='calling' WHERE key='role'")
                    original = {str(path.relative_to(root)): path.read_bytes()
                                for path in root.rglob("*") if path.is_file()}
                    selected = ("relay" if role == "vpn" else "vpn") if damage == "changed" else role
                    with patch("provider_operator.command") as command, \
                            patch("provider_operator.Supervisor") as supervisor, \
                            self.assertRaises(contract.Refusal) as refused:
                        self.open_operator(root, selected).close()
                    self.assertEqual(refused.exception.code, "binding")
                    command.assert_not_called()
                    supervisor.assert_not_called()
                    self.assertEqual({str(path.relative_to(root)): path.read_bytes()
                                      for path in root.rglob("*") if path.is_file()}, original)

    def test_descriptor_catalog_grant_and_rotation_keep_exact_selected_role(self) -> None:
        for role in ("vpn", "relay"):
            with self.subTest(role=role), self.provider(role) as provider:
                context = provider.context("test-device")
                now = int(time.time())
                descriptor = provider.descriptor()
                self.assertEqual(descriptor["roles"], [role])
                contract.descriptor(descriptor, context, now)
                wrong = "relay" if role == "vpn" else "vpn"
                other = contract.Context(context.provider, context.device_id, wrong, context.client_id,
                                         context.redirect_uri, context.accepted_issuer)
                with self.assertRaises(contract.Refusal) as refused:
                    contract.descriptor(descriptor, other, now)
                self.assertEqual(refused.exception.code, "unsupported")
                base = {"v": 1, "provider": context.provider, "device_id": context.device_id, "audience": context.audience}
                credential = "A" * 43
                catalog = provider.catalog(base, credential)
                self.assertEqual(catalog["services"][0]["role"], role)
                contract.catalog(catalog, descriptor, context, now)
                with self.assertRaises(contract.Refusal):
                    contract.catalog(catalog, descriptor, other, now)
                provider.operator.issue = Mock()
                previous = None
                for generation in (1, 2):
                    request = {**base, "request_id": "generation-" + str(generation), "service_id": "access",
                               "server_id": "edge", "role": role,
                               "previous_grant": previous["grant_id"] if previous else None}
                    response = {**request, "grant_id": "grant-" + str(generation), "generation": generation,
                                "issued_at": now, "refresh_after": now, "expires_at": now + 90,
                                "client_identity": str(generation) * 64, "kit_sha256": "a" * 64, "kit_size": 1068}
                    provider.operator.issue.return_value = response
                    actual = provider.grant(request, credential)
                    self.assertEqual(actual, response)
                    self.assertEqual(provider.operator.issue.call_args.args[1], request)
                    contract.grant(actual, request, previous, now)
                    provider.operator.issue.reset_mock()
                    with self.assertRaises(contract.Refusal) as refused:
                        provider.grant({**request, "role": wrong}, credential)
                    self.assertEqual(refused.exception.code, "unsupported")
                    provider.operator.issue.assert_not_called()
                    previous = response
                # A changed body reusing a journaled request stays a conflict.
                with provider.database:
                    provider.database.execute("INSERT INTO requests VALUES (?,?,?,?,?,?,?,?,?)",
                        (provider.authorize(context.device_id, credential)[0], request["request_id"],
                         encoded(request),
                         response["grant_id"], now + 90, time.monotonic() + 90, "active", None, now + 86400))
                with self.assertRaises(contract.Refusal) as refused:
                    provider.grant({**request, "role": wrong}, credential)
                self.assertEqual(refused.exception.code, "conflict")
                provider.operator.issue.assert_not_called()


if __name__ == "__main__":
    unittest.main()
