"""Exact callback registration controls for the optional Android test fixture."""

from __future__ import annotations

from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
from unittest.mock import patch
import unittest
from urllib.parse import parse_qs, urlsplit

import provider_conformance as contract
from reference_provider import Issuer, Provider, Registration
from test_reference_provider import FixtureProofKey, authorization, exchange_request


class RegistrationChecks(unittest.TestCase):
    def test_explicit_https_callback_exchanges_only_its_registered_code(self) -> None:
        registration = Registration("android-test-client", "https://callback.test.example/yume/oauth/callback")
        resource = "https://alpha.example/provider/v1"
        with tempfile.TemporaryDirectory() as directory:
            issuer = Issuer(Path(directory), "https://issuer.example", resource, registration)
            try:
                request, verifier = authorization(resource)
                request.update(client_id=registration.client_id, redirect_uri=registration.redirect_uri)
                callback = issuer.authorize(request, "entitled")
                self.assertEqual(callback.split("?")[0], registration.redirect_uri)
                code = parse_qs(urlsplit(callback).query)["code"][0]
                exchange = exchange_request(code, verifier, resource)
                exchange.update(client_id=registration.client_id, redirect_uri=registration.redirect_uri)
                key = FixtureProofKey(Path(directory))
                tokens = issuer.tokens(exchange, key.create("POST", issuer.origin + "/token"))
                self.assertEqual(tokens["scope"], "provider.enroll")
                refresh = {"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"],
                           "client_id": registration.client_id, "resource": resource}
                self.assertNotEqual(issuer.tokens(refresh, key.create("POST", issuer.origin + "/token"))["refresh_token"], tokens["refresh_token"])
            finally:
                issuer.database.close()

    def test_callback_substitution_sentinel_suffix_origin_and_client_are_rejected(self) -> None:
        registration = Registration("android-test-client", "https://callback.test.example/yume/oauth/callback")
        with tempfile.TemporaryDirectory() as directory:
            issuer = Issuer(Path(directory), "https://issuer.example", "https://alpha.example/provider/v1", registration)
            try:
                original, _ = authorization(issuer.resource)
                original.update(client_id=registration.client_id, redirect_uri=registration.redirect_uri)
                for callback in ("https://client.example/oauth/callback", registration.redirect_uri + "/",
                                 registration.redirect_uri + "?x=1", "https://evil.example/yume/oauth/callback"):
                    with self.subTest(callback=callback), self.assertRaises(contract.Refusal):
                        issuer.authorize({**original, "redirect_uri": callback}, "entitled")
                with self.assertRaises(contract.Refusal):
                    issuer.authorize({**original, "client_id": "another-client"}, "entitled")
                self.assertEqual(issuer.database.execute("SELECT count(*) FROM codes").fetchone()[0], 0)
            finally:
                issuer.database.close()

    def test_restart_cannot_rebind_existing_issuer_registration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            issuer = Issuer(root, "https://issuer.example", "https://alpha.example/provider/v1")
            issuer.database.close()
            with self.assertRaises(contract.Refusal):
                Issuer(root, "https://issuer.example", "https://alpha.example/provider/v1",
                       Registration("other-client", "https://other.example/callback"))

    def test_metadata_keeps_strict_bounds_with_a_one_second_clock_offset(self) -> None:
        with tempfile.TemporaryDirectory() as directory, sqlite3.connect(":memory:") as database:
            database.row_factory = sqlite3.Row
            database.execute("CREATE TABLE principals (device TEXT PRIMARY KEY, credential TEXT, expires INTEGER, owner TEXT)")
            server = Path(directory)
            (server / "credentials").mkdir()
            (server / "credentials/server-composite.pub.pem").write_bytes(b"synthetic-public-file")
            operator = SimpleNamespace(database=database, server=server, host="edge.example", port=443, role="vpn")
            provider = Provider(operator, "https://provider.example", None, None)
            context = provider.context("test-device")
            with patch("reference_provider.time.time", return_value=1000.01):
                descriptor = provider.descriptor()
                catalog = provider.catalog({"v": 1, "provider": context.provider,
                    "device_id": context.device_id, "audience": context.audience}, "A" * 43)
            with self.subTest(surface="descriptor"):
                contract.descriptor(descriptor, context, 999)
            with self.subTest(surface="catalog"):
                contract.catalog(catalog, descriptor, context, 999)
            with self.assertRaises(contract.Refusal):
                contract.catalog({**catalog, "issued_at": 1000}, descriptor, context, 999)

    def test_registration_has_no_http_wildcard_or_provider_chosen_url(self) -> None:
        for callback in ("http://callback.example/path", "testapp:/oauth", "https://*.example/callback",
                         "https://callback.example/a/../callback", "https://callback.example/callback#fragment"):
            with self.subTest(callback=callback), self.assertRaises(contract.Refusal):
                Registration("public-client", callback)


if __name__ == "__main__":
    unittest.main()
