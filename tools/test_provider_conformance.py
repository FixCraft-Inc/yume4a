"""Pinned synthetic provider cases. Run directly with Python, without Gradle."""

from copy import deepcopy
from collections.abc import Callable
from dataclasses import replace
import json
from pathlib import Path
import sys
import unittest

# Android does not ignore Python caches. These host-only cases create no output.
sys.dont_write_bytecode = True
import provider_conformance as contract


FIXTURES = Path(__file__).parent / "fixtures/providers.json"


class ProviderConformanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixtures = contract.decode(FIXTURES.read_bytes())
        self.now = self.fixtures["now"]
        self.alpha, self.beta = self.fixtures["operators"]
        self.tokens = self.fixtures["tokens"]
        self.manual = contract.KitEvidence(**self.fixtures["manual"])

    def context(self, operator: dict) -> contract.Context:
        registration = self.fixtures["registration"]
        auth = operator["descriptor"]["auth"]
        return contract.Context(operator["descriptor"]["provider"],
                                operator["catalog"]["device_id"], "vpn",
                                registration["client_id"], registration["redirect_uri"],
                                auth["issuer"] if auth else None)

    def evidence(self, operator: dict, g: dict | None = None) -> contract.KitEvidence:
        g = operator["grant"] if g is None else g
        s = operator["catalog"]["services"][0]["servers"][0]
        return contract.KitEvidence(g["kit_sha256"], g["kit_size"], s["host"], s["port"],
                                    s["server_key_sha256"], g["client_identity"])

    def refused(self, code: str, function: Callable, *args: object, **kwargs: object) -> None:
        with self.assertRaises(contract.Refusal) as caught:
            function(*args, **kwargs)
        self.assertEqual(caught.exception.code, code)
        self.assertEqual(str(caught.exception), code)

    def enrollment(self, operator: dict, *, installed: bool = False) -> contract.Enrollment:
        ctx = self.context(operator)
        d = contract.descriptor(operator["descriptor"], ctx, self.now)
        enrollment = contract.Enrollment(ctx, d)
        if d["auth"] is None:
            enrollment.anonymous_credential = self.fixtures["anonymous_device_token"]
            enrollment.anonymous_until = self.now + 2592000
        if d["auth"] is not None:
            enrollment.begin_authorization("S" * 43, self.now)
            enrollment.accept_callback({"iss": ctx.accepted_issuer, "state": "S" * 43,
                                        "code": "SYNTHETIC-CODE"}, ctx.redirect_uri, self.now)
            enrollment.store_tokens(self.tokens, self.now)
        if installed:
            epoch = enrollment.stage(operator["grant"], operator["request"], operator["catalog"],
                                     self.evidence(operator), self.now)
            enrollment.commit(epoch, self.now)
        return enrollment

    def replacement(self, operator: dict) -> tuple[dict, dict]:
        r = {**operator["request"], "request_id": "request-2",
             "previous_grant": operator["grant"]["grant_id"]}
        g = {**operator["grant"], **r, "grant_id": "grant-2", "generation": 2,
             "client_identity": "8" * 64, "kit_sha256": "9" * 64,
             "issued_at": self.now + 120, "refresh_after": self.now + 240,
             "expires_at": self.now + 720}
        return r, g

    def refreshed_account(self, enrollment: contract.Enrollment) -> None:
        enrollment.store_tokens({**self.tokens, "access_token": "SYNTHETIC-ACCESS-TWO",
                                 "refresh_token": "SYNTHETIC-REFRESH-TWO", "expires_in": 900},
                                self.now + 59)

    def test_two_independent_operators_and_manual_success(self) -> None:
        alpha = self.enrollment(self.alpha, installed=True)
        beta = self.enrollment(self.beta, installed=True)
        self.assertNotEqual(alpha.context.provider, beta.context.provider)
        for enrollment in (alpha, beta):
            enrollment.connect(self.now)
            self.assertTrue(enrollment.connected)
            self.assertEqual(enrollment.state, "Ready")
        self.assertIsNotNone(alpha.tokens)
        self.assertIsNone(beta.tokens)
        self.assertEqual(contract.manual_kit(self.manual), "5" * 64)

    def test_strict_json_rejections(self) -> None:
        for raw in (b'{"v":1,"v":1}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1.5}',
                    b'{"v":true}', b'{"v":false}', b'{"v":-1}', b'{"x":9007199254740992}',
                    b'{"x":"\\ud800"}', b'{"x":"\\u0001"}', b'\xef\xbb\xbf{}', b'\xff',
                    b'{} {}', b'{', b'[' * 9 + b'0' + b']' * 9,
                    json.dumps({"x": "a" * 4097}).encode(), b"[" + b"0," * 64 + b"0]",
                    b" " * 16383 + b"{}"):
            with self.subTest(raw=raw[:40]):
                self.refused("malformed", contract.decode, raw)
        self.assertEqual(contract.decode(b'{"x":"[\\\"{","y":null}'), {"x": '["{', "y": None})
        self.assertEqual(contract.decode(b"[" * 8 + b"0" + b"]" * 8), [[[[[[[[0]]]]]]]])
        self.assertEqual(contract.decode(b" " * 65534 + b"{}", 65536), {})
        self.refused("malformed", contract.decode, b" " * 65535 + b"{}", 65536)

    def test_origin_and_endpoint_substitutions(self) -> None:
        for value in ("http://alpha.example", "https://Alpha.example", "https://alpha.example/",
                      "https://alpha.example:443", "https://alpha.example:0443",
                      "https://alpha.example:65536", "https://user@alpha.example",
                      "https://alpha.example?x=1", "https://alpha.example#x",
                      "https://alpha.example%2eevil.example", "https://alpha.example.",
                      "https://127.0.0.1", "https://[::1]", "https://-alpha.example"):
            with self.subTest(value=value):
                with self.assertRaises(contract.Refusal):
                    contract.origin(value)
        self.assertEqual(contract.origin("https://self.example:8443"), "https://self.example:8443")
        for value in ("https://evil.example/token", "https://login.alpha.example/../token",
                      "https://login.alpha.example/%74oken", "https://login.alpha.example/token?x=1"):
            self.refused("binding", contract.endpoint, value, "https://login.alpha.example")

    def test_descriptor_closed_schema_and_bounds(self) -> None:
        ctx = self.context(self.alpha)
        changes = [("v", True, "malformed"), ("v", 2, "malformed"),
                   ("label", "x" * 81, "malformed"), ("label", "界" * 27, "malformed"),
                   ("expires_at", self.now, "stale"), ("expires_at", self.now + 3601, "stale"),
                   ("roles", [], "malformed"), ("required", ["sealed-kit-1"] * 17, "malformed"),
                   ("required", ["sealed-kit-1", "oidc-1"], "unsupported"),
                   ("required", self.alpha["descriptor"]["required"] + ["oidc-1"], "unsupported"),
                   ("required", ["sealed-kit-1", "browser-pkce-s256"], "unsupported"),
                   ("provider", self.beta["descriptor"]["provider"], "binding")]
        for key, value, code in changes:
            with self.subTest(key=key, value=value):
                self.refused(code, contract.descriptor, {**self.alpha["descriptor"], key: value}, ctx, self.now)
        self.refused("malformed", contract.descriptor, {**self.alpha["descriptor"], "account": "x"}, ctx, self.now)
        self.refused("unsupported", contract.descriptor, self.alpha["descriptor"], replace(ctx, role="unknown"), self.now)

    def test_issuer_and_registration_bindings(self) -> None:
        ctx = self.context(self.alpha)
        auth = self.alpha["descriptor"]["auth"]
        for key, value in (("issuer", "https://login.beta.example"),
                           ("token_endpoint", "https://beta.example/token"),
                           ("authorization_endpoint", "https://evil.example/auth"),
                           ("revocation_endpoint", "https://evil.example/revoke"),
                           ("client_id", "different-app"),
                           ("redirect_uri", "https://client.example/other"),
                           ("redirect_uri", "yume://callback")):
            with self.subTest(key=key):
                d = {**self.alpha["descriptor"], "auth": {**auth, key: value}}
                self.refused("binding", contract.descriptor, d, ctx, self.now)
        self.refused("binding", contract.descriptor, self.alpha["descriptor"],
                     replace(ctx, accepted_issuer=None), self.now)

    def test_authenticated_descriptor_requires_device_binding(self) -> None:
        for operator in (self.alpha, self.beta):
            description = deepcopy(operator["descriptor"])
            if description["auth"] is not None:
                description["required"].remove("device-bound-enrollment-1")
            else:
                description["required"].append("device-bound-enrollment-1")
            self.refused("unsupported", contract.descriptor, description, self.context(operator), self.now)
        self.refused("unsupported", contract.token_pair, {**self.tokens, "token_type": "Bearer"}, self.now)

    def test_country_is_closed_capability_bound_display_metadata(self) -> None:
        for value in (None, "US", "JP"):
            current = deepcopy(self.alpha["catalog"])
            current["services"][0]["servers"][0]["country"] = value
            contract.catalog(current, self.alpha["descriptor"], self.context(self.alpha), self.now)
        for value in ("us", "USA", "U1", "", 1, True):
            current = deepcopy(self.alpha["catalog"])
            current["services"][0]["servers"][0]["country"] = value
            self.refused("malformed", contract.catalog, current, self.alpha["descriptor"], self.context(self.alpha), self.now)
        missing = deepcopy(self.alpha["catalog"])
        del missing["services"][0]["servers"][0]["country"]
        self.refused("malformed", contract.catalog, missing, self.alpha["descriptor"], self.context(self.alpha), self.now)
        unlocated = deepcopy(self.alpha["descriptor"])
        unlocated["required"].remove("server-location-1")
        self.refused("malformed", contract.catalog, self.alpha["catalog"], unlocated, self.context(self.alpha), self.now)
        contract.catalog(missing, unlocated, self.context(self.alpha), self.now)

    def test_delivered_code_requires_exact_device_resource_and_grant(self) -> None:
        ctx = self.context(self.alpha)
        response = {key: self.alpha["grant"][key] for key in ("v", "provider", "device_id", "audience", "grant_id")}
        response["opening_code"] = "0123456789ABCDEFGHJKMNPQR"
        self.assertEqual(contract.opening_code(response, ctx, response["grant_id"]), response["opening_code"])
        for key, value in (("provider", "https://beta.example"), ("device_id", "other-device"),
                           ("audience", "https://beta.example/provider/v1"), ("grant_id", "other-grant")):
            self.refused("binding", contract.opening_code, {**response, key: value}, ctx, response["grant_id"])
        for value in ("0" * 24, "0" * 26, "I" * 25, "o" * 25, "0" * 24 + "-", None):
            self.refused("malformed", contract.opening_code, {**response, "opening_code": value}, ctx, response["grant_id"])
        self.refused("malformed", contract.opening_code, {**response, "token": "extra"}, ctx, response["grant_id"])

    def test_authorization_callback_consumption_and_bindings(self) -> None:
        ctx = self.context(self.alpha)
        attempt = contract.Attempt(ctx.provider, ctx.device_id, ctx.accepted_issuer,
                                   ctx.redirect_uri, "S" * 43, self.now)
        response = {"iss": ctx.accepted_issuer, "state": "S" * 43, "code": "SYNTHETIC-CODE"}
        contract.callback(response, ctx.redirect_uri, attempt, ctx, self.now)
        for key, value in (("iss", "https://login.beta.example"), ("state", "T" * 43)):
            self.refused("binding", contract.callback, {**response, key: value}, ctx.redirect_uri,
                         attempt, ctx, self.now)
        self.refused("binding", contract.callback, response, ctx.redirect_uri + "/extra", attempt, ctx, self.now)
        self.refused("binding", contract.callback, response, ctx.redirect_uri, attempt,
                     replace(ctx, device_id="other-device"), self.now)
        self.refused("binding", contract.callback, response, ctx.redirect_uri, attempt,
                     replace(ctx, provider="https://beta.example"), self.now)
        self.refused("stale", contract.callback, response, ctx.redirect_uri, attempt, ctx, self.now + 300)
        self.refused("malformed", contract.callback, {**response, "access_token": "x"},
                     ctx.redirect_uri, attempt, ctx, self.now)
        enrollment = self.enrollment(self.alpha)
        enrollment.begin_authorization("S" * 43, self.now)
        self.refused("state", enrollment.begin_authorization, "T" * 43, self.now)
        enrollment.accept_callback(response, ctx.redirect_uri, self.now)
        self.refused("state", enrollment.accept_callback, response, ctx.redirect_uri, self.now)
        enrollment.store_tokens(self.tokens, self.now)
        self.assertEqual(enrollment.state, "Authorized")
        enrollment.begin_authorization("S" * 43, self.now)
        self.refused("binding", enrollment.accept_callback, {**response, "state": "T" * 43}, ctx.redirect_uri, self.now)
        self.refused("state", enrollment.store_tokens, self.tokens, self.now)

    def test_code_exchange_deadline_is_not_reset_by_token_storage(self) -> None:
        enrollment = self.enrollment(self.alpha)
        enrollment.begin_authorization("S" * 43, self.now)
        enrollment.accept_callback({"iss": enrollment.context.accepted_issuer, "state": "S" * 43,
                                    "code": "SYNTHETIC-CODE"}, enrollment.context.redirect_uri, self.now)
        self.refused("stale", enrollment.store_tokens, self.tokens, self.now + 30)
        self.assertEqual(enrollment.state, "Added")
        self.refused("state", enrollment.store_tokens, self.tokens, self.now + 30)

    def test_tokens_require_authorization_and_public_provider_has_none(self) -> None:
        ctx = self.context(self.alpha)
        enrollment = contract.Enrollment(ctx, self.alpha["descriptor"])
        self.refused("state", enrollment.store_tokens, self.tokens, self.now)
        public = self.enrollment(self.beta)
        self.refused("state", public.store_tokens, self.tokens, self.now)
        public.api_allowed(self.now)
        self.assertIsNone(public.tokens)

    def test_anonymous_device_credential_owns_only_its_provider_and_device(self) -> None:
        ctx = self.context(self.beta)
        registry = contract.AnonymousDevices()
        credential = self.fixtures["anonymous_device_token"]
        self.refused("unauthorized", registry.authorize, ctx, ctx.provider, credential, self.now)
        registry.authorize(ctx, ctx.provider, credential, self.now, first_catalog=True)
        registry.authorize(ctx, ctx.provider, credential, self.now)
        self.refused("unauthorized", registry.authorize, ctx, ctx.provider, "E" * 42 + "A", self.now, first_catalog=True)
        self.refused("unauthorized", registry.authorize, replace(ctx, device_id="other-device"), ctx.provider, credential, self.now)
        self.refused("binding", registry.authorize, ctx, "https://alpha.example", credential, self.now)
        self.refused("malformed", registry.authorize, ctx, ctx.provider, "D" * 43, self.now)
        self.refused("unauthorized", registry.authorize, ctx, ctx.provider, credential, self.now + 2592000)
        self.assertEqual(len(registry.owners), 1)
        public = self.enrollment(self.beta)
        public.anonymous_credential = None
        self.refused("unauthorized", public.api_allowed, self.now)
        public = self.enrollment(self.beta)
        # Fresh descriptor isolates credential expiry from descriptor staleness.
        public.description["expires_at"] = public.anonymous_until + 3600
        self.refused("unauthorized", public.api_allowed, public.anonymous_until)

    def test_anonymous_device_quota_refuses_before_allocating(self) -> None:
        ctx = self.context(self.beta)
        registry = contract.AnonymousDevices(max_devices=1)
        credential = self.fixtures["anonymous_device_token"]
        registry.authorize(ctx, ctx.provider, credential, self.now, first_catalog=True)
        self.refused("rate_limited", registry.authorize, replace(ctx, device_id="device-2"),
                     ctx.provider, credential, self.now, first_catalog=True)
        self.assertEqual(len(registry.owners), 1)

    def test_http_redirect_compression_and_deadline_refusals(self) -> None:
        args = ("application/json", None, 29999, "https://alpha.example", "https://alpha.example")
        contract.http_boundary(200, *args)
        for status in (301, 302, 307, 308):
            self.refused("binding", contract.http_boundary, status, *args)
        self.refused("binding", contract.http_boundary, 200, "application/json", None, 0,
                     "https://beta.example", "https://alpha.example")
        self.refused("malformed", contract.http_boundary, 200, "application/json", "gzip", 0,
                     "https://alpha.example", "https://alpha.example")
        self.refused("unavailable", contract.http_boundary, 200, "application/json", None, 30000,
                     "https://alpha.example", "https://alpha.example")

    def test_token_bounds_rotation_and_failed_persistence(self) -> None:
        for key, value, code in (("expires_in", 59, "malformed"), ("expires_in", 901, "malformed"),
                                 ("expires_in", True, "malformed"), ("refresh_expires_in", 60, "malformed"),
                                 ("refresh_expires_in", 2592001, "malformed"),
                                 ("scope", "transport.psk", "unsupported"), ("token_type", "Basic", "unsupported"),
                                 ("access_token", "x" * 4097, "malformed")):
            self.refused(code, contract.token_pair, {**self.tokens, key: value}, self.now)
        enrollment = self.enrollment(self.alpha)
        old = enrollment.tokens
        self.refreshed_account(enrollment)
        self.assertNotEqual(old.refresh, enrollment.tokens.refresh)
        self.refused("revoked", enrollment.store_tokens,
                     {**self.tokens, "access_token": enrollment.tokens.access,
                      "refresh_token": enrollment.tokens.refresh}, self.now + 59)
        self.assertIsNone(enrollment.tokens)
        enrollment = self.enrollment(self.alpha)
        self.refused("storage", enrollment.store_tokens,
                     {**self.tokens, "access_token": "NEW-ACCESS", "refresh_token": "NEW-REFRESH"},
                     self.now + 1, committed=False)
        self.assertIsNone(enrollment.tokens)
        self.assertEqual(enrollment.state, "Suspended")

    def test_catalog_bindings_and_freshness(self) -> None:
        for operator in (self.alpha, self.beta):
            ctx = self.context(operator)
            c, d = operator["catalog"], operator["descriptor"]
            self.assertEqual(contract.catalog(c, d, ctx, self.now), c)
            for key, value, code in (("device_id", "other-device", "binding"),
                                     ("provider", "https://third.example", "binding"),
                                     ("audience", "https://third.example/provider/v1", "binding"),
                                     ("expires_at", self.now, "stale"),
                                     ("expires_at", self.now + 301, "stale"),
                                     ("issued_at", self.now + 1, "stale"),
                                     ("services", c["services"] * 33, "malformed")):
                with self.subTest(provider=ctx.provider, key=key):
                    self.refused(code, contract.catalog, {**c, key: value}, d, ctx, self.now)
            self.assertEqual(contract.catalog({**c, "services": []}, d, ctx, self.now)["services"], [])

    def test_catalog_server_and_service_rejections(self) -> None:
        c, d, ctx = self.alpha["catalog"], self.alpha["descriptor"], self.context(self.alpha)
        for key, value, code in (("role", "relay", "unsupported"),
                                 ("required", ["unknown-required"], "unsupported"),
                                 ("servers", [], "malformed")):
            changed = deepcopy(c)
            changed["services"][0][key] = value
            self.refused(code, contract.catalog, changed, d, ctx, self.now)
        for key, value in (("host", "127.0.0.1"), ("port", 0), ("port", True),
                           ("server_key_sha256", "A" * 64), ("id", "x" * 65)):
            changed = deepcopy(c)
            changed["services"][0]["servers"][0][key] = value
            self.refused("malformed", contract.catalog, changed, d, ctx, self.now)
        duplicate = deepcopy(c)
        duplicate["services"] *= 2
        self.refused("malformed", contract.catalog, duplicate, d, ctx, self.now)
        duplicate = deepcopy(c)
        duplicate["services"][0]["servers"] *= 2
        self.refused("malformed", contract.catalog, duplicate, d, ctx, self.now)

    def test_requests_require_current_entitlement_and_exact_selection(self) -> None:
        ctx = self.context(self.alpha)
        c, r = self.alpha["catalog"], self.alpha["request"]
        for key, value, code in (("device_id", "wrong-device", "binding"),
                                 ("audience", "https://beta.example/provider/v1", "binding"),
                                 ("role", "relay", "unsupported"), ("service_id", "unlisted", "not_entitled"),
                                 ("server_id", "beta-edge", "not_entitled")):
            self.refused(code, contract.request, {**r, key: value}, ctx, c, self.now)
        self.refused("stale", contract.request, r, ctx, c, self.now + 300)
        self.refused("not_entitled", contract.request, r, ctx, {**c, "services": []}, self.now)

    def test_grant_binding_types_bounds_and_lease(self) -> None:
        g, r = self.alpha["grant"], self.alpha["request"]
        for key, value, code in (("device_id", "wrong-device", "binding"),
                                 ("provider", "https://beta.example", "binding"),
                                 ("audience", "https://beta.example/provider/v1", "binding"),
                                 ("request_id", "other-request", "binding"),
                                 ("service_id", "other-service", "binding"),
                                 ("server_id", "beta-edge", "binding"),
                                 ("role", "relay", "binding"), ("v", True, "binding"),
                                 ("expires_at", self.now, "stale"),
                                 ("expires_at", self.now + 3601, "stale"),
                                 ("issued_at", self.now + 1, "stale"),
                                 ("refresh_after", g["expires_at"], "stale"),
                                 ("generation", 2, "state"), ("generation", True, "malformed"),
                                 ("kit_size", 1069, "malformed"), ("kit_size", 1049649, "malformed")):
            with self.subTest(key=key, value=value):
                self.refused(code, contract.grant, {**g, key: value}, r, None, self.now)

    def test_transport_refresh_rotation_and_no_early_refresh(self) -> None:
        previous = self.alpha["grant"]
        r, g = self.replacement(self.alpha)
        self.assertEqual(contract.grant(g, r, previous, self.now + 120), g)
        # Issue time is adjusted so the early-refresh guard is the causal check.
        early = {**g, "issued_at": self.now + 119}
        self.refused("state", contract.grant, early, r, previous, self.now + 119)
        for key, value in (("generation", 1), ("grant_id", previous["grant_id"]),
                           ("client_identity", previous["client_identity"])):
            self.refused("state", contract.grant, {**g, key: value}, r, previous, self.now + 120)
        self.refused("binding", contract.grant, g, r,
                     {**previous, "device_id": "other-device"}, self.now + 120)

    def test_native_evidence_and_selection_must_match_before_install(self) -> None:
        e = self.evidence(self.alpha)
        for key, value, code in (("sha256", "f" * 64, "binding"), ("host", "edge.beta.example", "binding"),
                                 ("port", 8443, "binding"), ("server_key_sha256", "b" * 64, "binding"),
                                 ("client_identity", "f" * 64, "binding"), ("size", 2092, "binding"),
                                 ("native_client_valid", False, "unsupported"),
                                 ("contained_paths", False, "unsupported"),
                                 ("app_policy_valid", False, "unsupported")):
            enrollment = self.enrollment(self.alpha)
            self.refused(code, enrollment.stage, self.alpha["grant"], self.alpha["request"],
                         self.alpha["catalog"], replace(e, **{key: value}), self.now)
            self.assertIsNone(enrollment.installed)
            self.assertEqual(enrollment.state, "Authorized")
        enrollment = self.enrollment(self.alpha)
        forged_catalog = {**self.alpha["catalog"], "provider": "https://beta.example"}
        self.refused("binding", enrollment.stage, self.alpha["grant"], self.alpha["request"],
                     forged_catalog, e, self.now)

    def test_failed_or_precommit_crash_never_publishes_partial_install(self) -> None:
        for outcome in ("failed", "crash_before"):
            enrollment = self.enrollment(self.alpha)
            epoch = enrollment.stage(self.alpha["grant"], self.alpha["request"], self.alpha["catalog"],
                                     self.evidence(self.alpha), self.now)
            self.refused("storage", enrollment.commit, epoch, self.now, outcome=outcome)
            self.assertIsNone(enrollment.installed)
            self.assertIsNone(enrollment.staged)
            self.assertEqual(enrollment.state, "Authorized")

    def test_install_cannot_cross_expiry_or_run_concurrently(self) -> None:
        enrollment = self.enrollment(self.beta)
        epoch = enrollment.stage(self.beta["grant"], self.beta["request"], self.beta["catalog"],
                                 self.evidence(self.beta), self.now)
        self.refused("state", enrollment.stage, self.beta["grant"], self.beta["request"], self.beta["catalog"],
                     self.evidence(self.beta), self.now)
        self.refused("stale", enrollment.commit, epoch, self.now + 600)
        self.refused("state", enrollment.connect, self.now + 600)
        self.assertIsNone(enrollment.installed)

    def test_cancelled_completion_cannot_commit_a_new_attempt(self) -> None:
        enrollment = self.enrollment(self.beta)
        args = (self.beta["grant"], self.beta["request"], self.beta["catalog"],
                self.evidence(self.beta), self.now)
        old_epoch = enrollment.stage(*args)
        enrollment.cancel()
        new_epoch = enrollment.stage(*args)
        self.refused("state", enrollment.commit, old_epoch, self.now)
        self.assertIsNone(enrollment.installed)
        enrollment.commit(new_epoch, self.now)
        self.assertEqual(enrollment.state, "Ready")

    def test_postcommit_crash_retains_complete_generation(self) -> None:
        enrollment = self.enrollment(self.beta)
        epoch = enrollment.stage(self.beta["grant"], self.beta["request"], self.beta["catalog"],
                                 self.evidence(self.beta), self.now)
        enrollment.commit(epoch, self.now, outcome="crash_after")
        self.assertEqual(enrollment.installed, self.beta["grant"])
        self.assertEqual(enrollment.state, "Ready")

    def test_failed_refresh_preserves_record_but_cannot_resurrect_remote_identity(self) -> None:
        enrollment = self.enrollment(self.alpha, installed=True)
        self.refreshed_account(enrollment)
        r, g = self.replacement(self.alpha)
        epoch = enrollment.stage(g, r, self.alpha["catalog"], self.evidence(self.alpha, g), self.now + 120)
        self.refused("storage", enrollment.commit, epoch, self.now + 120, outcome="failed")
        self.assertEqual(enrollment.installed, self.alpha["grant"])
        self.assertEqual(enrollment.state, "Suspended")
        self.refused("state", enrollment.connect, self.now + 120)

    def test_successful_refresh_replaces_one_complete_generation(self) -> None:
        enrollment = self.enrollment(self.alpha, installed=True)
        self.refreshed_account(enrollment)
        r, g = self.replacement(self.alpha)
        epoch = enrollment.stage(g, r, self.alpha["catalog"], self.evidence(self.alpha, g), self.now + 120)
        enrollment.commit(epoch, self.now + 120)
        self.assertEqual(enrollment.installed, g)
        self.assertIsNone(enrollment.staged)
        self.assertIsNotNone(enrollment.tokens)
        enrollment.connect(self.now + 120)
        self.assertTrue(enrollment.connected)

    def test_account_token_expiry_does_not_expire_transport(self) -> None:
        enrollment = self.enrollment(self.alpha, installed=True)
        self.refused("unauthorized", enrollment.api_allowed, self.now + 60)
        enrollment.connect(self.now + 60)
        self.assertTrue(enrollment.connected)

    def test_expiry_revocation_and_untrusted_clock_stop_existing_connections(self) -> None:
        for event in ("expiry", "revoked", "clock"):
            enrollment = self.enrollment(self.alpha, installed=True)
            enrollment.connect(self.now)
            at = self.now + (600 if event == "expiry" else 1)
            enrollment.expire_or_revoke(at, revoked=event == "revoked", trusted_clock=event != "clock")
            self.assertEqual(enrollment.state, "Suspended")
            self.assertFalse(enrollment.connected)
            self.refused("state", enrollment.connect, at)
        # Account refresh must not restore a revoked but unexpired transport grant.
        enrollment = self.enrollment(self.alpha, installed=True)
        enrollment.expire_or_revoke(self.now + 1, revoked=True)
        self.refreshed_account(enrollment)
        self.refused("state", enrollment.connect, self.now + 59)

    def test_cancel_and_removal_invalidate_late_install_and_callback(self) -> None:
        for action in ("cancel", "remove"):
            enrollment = self.enrollment(self.beta)
            epoch = enrollment.stage(self.beta["grant"], self.beta["request"], self.beta["catalog"],
                                     self.evidence(self.beta), self.now)
            getattr(enrollment, action)()
            self.refused("state", enrollment.commit, epoch, self.now)
            self.assertIsNone(enrollment.installed)
        enrollment = self.enrollment(self.alpha)
        enrollment.begin_authorization("S" * 43, self.now)
        enrollment.remove()
        self.refused("state", enrollment.accept_callback,
                     {"iss": enrollment.context.accepted_issuer, "state": "S" * 43, "code": "LATE"},
                     enrollment.context.redirect_uri, self.now)
        self.refused("state", enrollment.store_tokens, self.tokens, self.now)

    def test_offline_removal_preserves_other_operator_manual_and_local_identity(self) -> None:
        alpha = self.enrollment(self.alpha, installed=True)
        beta = self.enrollment(self.beta, installed=True)
        manual = {"manual-kit": contract.manual_kit(self.manual)}
        local_identity = {"communication-key-owner": "local", "contact-key": "UNCHANGED"}
        preferences = {"shorter-route-consent": False, "direct-routing-consent": False}
        snapshot = deepcopy((beta, manual, local_identity, preferences))
        self.assertEqual(alpha.remove(), "unknown")
        self.assertEqual(alpha.state, "Removed")
        self.assertIsNone(alpha.tokens)
        self.assertIsNone(alpha.installed)
        self.assertIsNone(alpha.anonymous_credential)
        self.assertEqual((beta, manual, local_identity, preferences), snapshot)
        self.refused("state", alpha.connect, self.now)
        self.assertEqual(alpha.remove(remote_confirmed=True), "confirmed")
        locked = self.enrollment(self.alpha, installed=True)
        locked.remove(vault_locked=True)
        self.assertTrue(locked.cleanup_pending)
        self.refused("state", locked.connect, self.now)

    def test_revoke_confirmation_requires_exact_ownership(self) -> None:
        r = {key: self.alpha["request"][key] for key in contract.BASE_FIELDS.split()}
        r.update(request_id="revoke-request", grant_id="alpha-grant-1")
        response = {**r, "status": "revoked"}
        contract.revoke_response(response, r, self.context(self.alpha))
        for key, value in (("status", "pending"), ("grant_id", "beta-grant-1"),
                           ("provider", "https://beta.example"), ("device_id", "other-device")):
            self.refused("binding", contract.revoke_response, {**response, key: value}, r, self.context(self.alpha))

    def test_idempotency_replay_conflict_and_original_expiry(self) -> None:
        journal = contract.Idempotency()
        r, g = self.alpha["request"], self.alpha["grant"]
        self.assertEqual(journal.publish(r, g, self.now), g)
        # An implementation must return the original lease on an identical retry.
        later = journal.publish(r, {**g, "expires_at": self.now + 3600}, self.now + 10)
        self.assertEqual(later, g)
        self.refused("conflict", journal.publish, {**r, "device_id": "other-device"}, g, self.now + 10)
        self.refused("stale", journal.publish, r, g, self.now + 86400)

    def test_idempotency_capacity_preserves_live_results(self) -> None:
        journal = contract.Idempotency()
        r, g = self.beta["request"], self.beta["grant"]
        for index in range(64):
            journal.publish({**r, "request_id": f"request-{index}"}, g, self.now)
        self.refused("rate_limited", journal.publish, {**r, "request_id": "overflow"}, g, self.now)
        self.assertEqual(len(journal.entries), 64)
        self.assertEqual(journal.publish({**r, "request_id": "request-0"}, g, self.now + 1), g)

    def test_device_and_generation_identities_are_independent(self) -> None:
        ledger = contract.IdentityLedger()
        ledger.claim(self.alpha["grant"])
        ledger.claim(self.beta["grant"])
        ledger.claim(self.alpha["grant"])
        for key, value in (("device_id", "other-device"), ("grant_id", "alpha-grant-2"),
                           ("provider", "https://beta.example")):
            self.refused("binding", ledger.claim, {**self.alpha["grant"], key: value})

    def test_manual_validation_and_no_provider_fields(self) -> None:
        self.assertEqual(contract.manual_kit(self.manual), "5" * 64)
        for key in ("native_client_valid", "contained_paths", "app_policy_valid"):
            self.refused("unsupported", contract.manual_kit, replace(self.manual, **{key: False}))
        # Manual setup has no provider, account token, managed lease or remote call.
        self.assertEqual(set(self.fixtures["manual"]),
                         {"sha256", "size", "host", "port", "server_key_sha256", "client_identity"})

    def test_errors_are_closed_bounded_and_safe_for_display(self) -> None:
        contract.error_response({"v": 1, "error": "rate_limited", "retry_after": 300})
        for value in ({"v": 1, "error": "binding", "retry_after": 1},
                      {"v": 1, "error": "rate_limited", "retry_after": 301},
                      {"v": 1, "error": "peer-controlled-message", "retry_after": 0},
                      {"v": 1, "error": "binding", "retry_after": 0, "detail": "TOKEN"}):
            self.refused("malformed", contract.error_response, value)

    def test_each_message_schema_rejects_account_extensions(self) -> None:
        ctx, d, c, r, g = (self.context(self.alpha), self.alpha["descriptor"],
                           self.alpha["catalog"], self.alpha["request"], self.alpha["grant"])
        extra = {"account_policy": "must-stay-outside-native-schema"}
        self.refused("malformed", contract.catalog, {**c, **extra}, d, ctx, self.now)
        self.refused("malformed", contract.request, {**r, **extra}, ctx, c, self.now)
        self.refused("malformed", contract.grant, {**g, **extra}, r, None, self.now)
        self.refused("malformed", contract.token_pair, {**self.tokens, **extra}, self.now)


if __name__ == "__main__":
    unittest.main(verbosity=2)
