"""Loopback HTTPS providers and local issuer for native conformance only."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import base64
from contextlib import ExitStack
import hashlib
import hmac
import http.server
import html
import json
import os
import re
from pathlib import Path
import select
import secrets
import signal
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qs, urlencode, urlsplit

sys.dont_write_bytecode = True
import provider_conformance as contract
from provider_operator import Operator, atomic, encoded, private_directory

STATUS = {"malformed": 400, "binding": 400, "unsupported": 422, "unauthorized": 401,
          "not_entitled": 403, "conflict": 409, "stale": 409, "revoked": 410,
          "rate_limited": 429, "unavailable": 503}


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def base64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def unbase64url(value: str, size: int | None = None) -> bytes:
    contract.require(type(value) is str and re.fullmatch(r"[A-Za-z0-9_-]+", value) is not None, "unauthorized")
    try:
        raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except ValueError:
        raise contract.Refusal("unauthorized") from None
    contract.require(base64url(raw) == value and (size is None or len(raw) == size), "unauthorized")
    return raw


def proof(database: sqlite3.Connection, value: str | None, method: str, url: str,
          access: str | None = None) -> str:
    """Verify the closed RFC 9449 profile with OpenSSL, then consume key/jti.

    Only public verification inputs enter the temporary files. No Python EC
    implementation or fixture-only signature bypass is used.
    """
    contract.require(type(value) is str and 0 < len(value) <= 2048, "unauthorized")
    parts = value.split(".")
    contract.require(len(parts) == 3, "unauthorized")
    try:
        header = contract.object_fields(contract.decode(unbase64url(parts[0]), 2048), "typ alg jwk")
        jwk = contract.object_fields(header["jwk"], "kty crv x y")
        claims = contract.object_fields(contract.decode(unbase64url(parts[1]), 2048),
                                        "jti htm htu iat" + (" ath" if access is not None else ""))
        contract.require(header["typ"] == "dpop+jwt" and header["alg"] == "ES256" and
                         jwk["kty"] == "EC" and jwk["crv"] == "P-256", "unauthorized")
        x, y = unbase64url(jwk["x"], 32), unbase64url(jwk["y"], 32)
        unbase64url(claims["jti"], 32)
        contract.integer(claims["iat"])
        contract.require(claims["htm"] == method and claims["htu"] == url and
                         abs(int(time.time()) - claims["iat"]) <= 60, "unauthorized")
        if access is not None:
            contract.require(claims["ath"] == base64url(hashlib.sha256(access.encode("ascii")).digest()), "unauthorized")
        signature = unbase64url(parts[2], 64)
    except (contract.Refusal, UnicodeError):
        raise contract.Refusal("unauthorized") from None
    # Standard DER encodings for id-ecPublicKey, prime256v1 and ECDSA R/S.
    public = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004") + x + y
    integers = []
    for half in (signature[:32], signature[32:]):
        half = half.lstrip(b"\0") or b"\0"
        if half[0] & 0x80:
            half = b"\0" + half
        integers.append(b"\x02" + bytes([len(half)]) + half)
    der = b"".join(integers)
    with tempfile.TemporaryDirectory(prefix="provider-proof-") as name:
        root = Path(name)
        (root / "public.der").write_bytes(public)
        (root / "signature.der").write_bytes(b"\x30" + bytes([len(der)]) + der)
        try:
            verified = subprocess.run(["/usr/bin/openssl", "dgst", "-sha256", "-verify", str(root / "public.der"),
                "-keyform", "DER", "-signature", str(root / "signature.der")],
                input=(parts[0] + "." + parts[1]).encode("ascii"), capture_output=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            raise contract.Refusal("unavailable") from None
    contract.require(verified.returncode == 0, "unauthorized")
    canonical = json.dumps({"crv": "P-256", "kty": "EC", "x": jwk["x"], "y": jwk["y"]}, separators=(",", ":"))
    thumbprint = base64url(hashlib.sha256(canonical.encode("ascii")).digest())
    now = int(time.time())
    with database:
        database.execute("DELETE FROM proofs WHERE until<?", (now,))
        contract.require(database.execute("SELECT count(*) FROM proofs").fetchone()[0] < 4096, "rate_limited")
        try:
            database.execute("INSERT INTO proofs VALUES (?,?,?)", (thumbprint, claims["jti"], now + 120))
        except sqlite3.IntegrityError:
            raise contract.Refusal("unauthorized") from None
    return thumbprint


def form(raw: bytes) -> dict[str, str]:
    contract.require(len(raw) <= 16384)
    try:
        entries = parse_qs(raw.decode("ascii"), strict_parsing=True, max_num_fields=16)
    except (ValueError, UnicodeError):
        raise contract.Refusal("malformed") from None
    contract.require(all(len(value) == 1 for value in entries.values()))
    return {key: value[0] for key, value in entries.items()}


@dataclass(frozen=True)
class Registration:
    """Exact public registration for this test fixture; no wildcard callback."""

    client_id: str = "synthetic-public-client"
    redirect_uri: str = "https://client.example/oauth/callback"

    def __post_init__(self) -> None:
        contract.identifier(self.client_id)
        contract.endpoint(self.redirect_uri)


class Issuer:
    """Opaque, single-use codes and rotating token families for local tests."""

    def __init__(self, root: Path, origin: str, resource: str,
                 registration: Registration = Registration()) -> None:
        self.origin, self.resource = origin, resource
        self.registration = registration
        self.database = sqlite3.connect(root / "issuer.sqlite")
        self.database.row_factory = sqlite3.Row
        self.database.execute("PRAGMA synchronous=FULL")
        self.database.executescript("""
          CREATE TABLE IF NOT EXISTS codes (hash TEXT PRIMARY KEY, request TEXT NOT NULL, subject TEXT NOT NULL, until INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, family TEXT NOT NULL,
            resource TEXT NOT NULL, subject TEXT NOT NULL, until INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0, key_thumbprint TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS proofs (thumbprint TEXT NOT NULL, jti TEXT NOT NULL, until INTEGER NOT NULL, PRIMARY KEY(thumbprint,jti));
          CREATE TABLE IF NOT EXISTS revoked (family TEXT PRIMARY KEY);
          CREATE TABLE IF NOT EXISTS registration (client_id TEXT NOT NULL, redirect_uri TEXT NOT NULL);
        """)
        try:
            contract.require("key_thumbprint" in {row[1] for row in self.database.execute("PRAGMA table_info(tokens)")}, "binding")
            existing = self.database.execute("SELECT * FROM registration").fetchall()
            expected = (registration.client_id, registration.redirect_uri)
            if not existing:
                contract.require(self.database.execute("SELECT count(*) FROM tokens").fetchone()[0] == 0 and
                                 self.database.execute("SELECT count(*) FROM codes").fetchone()[0] == 0, "binding")
                with self.database:
                    self.database.execute("INSERT INTO registration VALUES (?,?)", expected)
            else:
                contract.require(len(existing) == 1 and tuple(existing[0]) == expected, "binding")
        except BaseException:
            self.database.close()
            raise

    def authorize(self, request: dict, subject: str) -> str:
        contract.object_fields(request, "response_type client_id redirect_uri scope state code_challenge code_challenge_method resource")
        contract.require(request["response_type"] == "code" and request["client_id"] == self.registration.client_id and
                         request["redirect_uri"] == self.registration.redirect_uri and
                         request["scope"] == "provider.enroll" and request["resource"] == self.resource and
                         request["code_challenge_method"] == "S256", "binding")
        contract.device_credential(request["state"])
        contract.device_credential(request["code_challenge"])
        contract.require(subject in ("entitled", "denied"), "unauthorized")
        code = secrets.token_urlsafe(32)
        with self.database:
            self.database.execute("DELETE FROM codes WHERE until<=?", (int(time.time()),))
            contract.require(self.database.execute("SELECT count(*) FROM codes").fetchone()[0] < 32, "rate_limited")
            self.database.execute("INSERT INTO codes VALUES (?,?,?,?)",
                                  (fingerprint(code), encoded(request), subject, int(time.time()) + 300))
        return request["redirect_uri"] + "?" + urlencode({"iss": self.origin, "state": request["state"], "code": code})

    def tokens(self, request: dict, dpop: str | None = None) -> dict:
        key = proof(self.database, dpop, "POST", self.origin + "/token")
        now = int(time.time())
        with self.database:
            self.database.execute("DELETE FROM tokens WHERE until<=?", (now,))
            self.database.execute("DELETE FROM revoked WHERE family NOT IN (SELECT family FROM tokens)")
        contract.require(self.database.execute("SELECT count(*) FROM tokens").fetchone()[0] < 256, "rate_limited")
        if request.get("grant_type") == "authorization_code":
            contract.object_fields(request, "grant_type code client_id redirect_uri code_verifier resource")
            row = self.database.execute("SELECT * FROM codes WHERE hash=?", (fingerprint(request["code"]),)).fetchone()
            contract.require(row is not None, "unauthorized")
            with self.database:
                self.database.execute("DELETE FROM codes WHERE hash=?", (fingerprint(request["code"]),))
            old = json.loads(row["request"])
            contract.device_credential(request["code_verifier"])
            challenge = base64.urlsafe_b64encode(hashlib.sha256(request["code_verifier"].encode()).digest()).decode().rstrip("=")
            contract.require(row["until"] > now and request["resource"] == self.resource and
                             request["client_id"] == old["client_id"] and request["redirect_uri"] == old["redirect_uri"] and
                             hmac.compare_digest(challenge, old["code_challenge"]), "unauthorized")
            family, subject = secrets.token_hex(16), row["subject"]
        else:
            contract.object_fields(request, "grant_type refresh_token client_id resource")
            contract.require(request["grant_type"] == "refresh_token", "unsupported")
            row = self.database.execute("SELECT * FROM tokens WHERE hash=? AND kind='refresh'", (fingerprint(request["refresh_token"]),)).fetchone()
            contract.require(row is not None, "unauthorized")
            family, subject = row["family"], row["subject"]
            if row["used"] or not hmac.compare_digest(row["key_thumbprint"], key):
                with self.database:
                    self.database.execute("INSERT OR IGNORE INTO revoked VALUES (?)", (family,))
                raise contract.Refusal("unauthorized")
            contract.require(row["until"] > now and request["resource"] == row["resource"] == self.resource and
                             request["client_id"] == self.registration.client_id and
                             self.database.execute("SELECT 1 FROM revoked WHERE family=?", (family,)).fetchone() is None, "unauthorized")
            with self.database:
                self.database.execute("UPDATE tokens SET used=1 WHERE hash=?", (row["hash"],))
        access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with self.database:
            for kind, value, lifetime in (("access", access, 60), ("refresh", refresh, 3600)):
                self.database.execute("INSERT INTO tokens VALUES (?,?,?,?,?,?,0,?)",
                                      (fingerprint(value), kind, family, self.resource, subject, now + lifetime, key))
        return {"token_type": "DPoP", "access_token": access, "expires_in": 60,
                "refresh_token": refresh, "refresh_expires_in": 3600, "scope": "provider.enroll"}

    def revoke(self, request: dict) -> dict:
        contract.object_fields(request, "token client_id")
        contract.require(request["client_id"] == self.registration.client_id, "binding")
        with self.database:
            self.database.execute("INSERT OR IGNORE INTO revoked SELECT family FROM tokens WHERE hash=?", (fingerprint(request["token"]),))
        return {}


class Provider:
    def __init__(self, operator: Operator, origin: str, issuer_origin: str | None, issuer_root: Path | None,
                 registration: Registration = Registration()) -> None:
        self.operator, self.origin, self.issuer_origin = operator, origin, issuer_origin
        self.issuer_root = issuer_root
        self.registration = registration
        self.database = operator.database
        self.database.execute("CREATE TABLE IF NOT EXISTS catalogs (principal TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        self.database.execute("CREATE TABLE IF NOT EXISTS proofs (thumbprint TEXT NOT NULL, jti TEXT NOT NULL, until INTEGER NOT NULL, PRIMARY KEY(thumbprint,jti))")
        self.database.execute("CREATE TABLE IF NOT EXISTS grant_families (grant_id TEXT PRIMARY KEY, family TEXT NOT NULL)")
        self.database.commit()

    def context(self, device: str) -> contract.Context:
        return contract.Context(self.origin, device, self.operator.role, self.registration.client_id,
                                self.registration.redirect_uri, self.issuer_origin)

    def descriptor(self) -> dict:
        auth = None
        required = ["sealed-kit-1", "device-grants-1", "anonymous-device-1"]
        if self.issuer_origin is not None:
            required[-1:] = ["browser-pkce-s256", "device-bound-enrollment-1"]
            auth = {"issuer": self.issuer_origin, "authorization_endpoint": self.issuer_origin + "/authorize",
                    "token_endpoint": self.issuer_origin + "/token", "revocation_endpoint": self.issuer_origin + "/revoke",
                    "client_id": self.registration.client_id, "redirect_uri": self.registration.redirect_uri}
        required.append("server-location-1")
        return {"v": 1, "provider": self.origin, "label": "Synthetic provider", "expires_at": int(time.time()) + 3599,
                "required": required, "roles": [self.operator.role], "auth": auth}

    def authorize(self, device: str, credential: str, *, register: bool = False,
                  dpop: str | None = None, method: str = "POST", path: str = "/provider/v1/catalog") -> tuple[str, bool, str | None]:
        now = int(time.time())
        contract.identifier(device)
        if self.issuer_origin is not None:
            with sqlite3.connect(f"file:{self.issuer_root / 'issuer.sqlite'}?mode=ro", uri=True) as issuer:
                issuer.row_factory = sqlite3.Row
                token = issuer.execute("SELECT * FROM tokens WHERE hash=? AND kind='access'", (fingerprint(credential),)).fetchone()
                contract.require(token is not None and token["until"] > now and token["resource"] == self.origin + "/provider/v1", "unauthorized")
                contract.require(issuer.execute("SELECT 1 FROM revoked WHERE family=?", (token["family"],)).fetchone() is None, "unauthorized")
                key = proof(self.database, dpop, method, self.origin + path, credential)
                contract.require(hmac.compare_digest(token["key_thumbprint"], key), "unauthorized")
                family = token["family"]
                owner, entitled = token["subject"], token["subject"] == "entitled"
        else:
            contract.device_credential(credential)
            family = None
            owner, entitled = fingerprint(credential), True
        stored = self.database.execute("SELECT * FROM principals WHERE device=?", (device,)).fetchone()
        if stored is None:
            contract.require(register, "unauthorized")
            limit = 3 if self.issuer_origin is not None else 2
            contract.require(self.database.execute("SELECT count(*) FROM principals").fetchone()[0] < limit, "rate_limited")
            with self.database:
                self.database.execute("INSERT INTO principals VALUES (?,?,?,?)", (device, fingerprint(credential), now + 2592000, owner))
        else:
            contract.require(stored["expires"] > now and hmac.compare_digest(stored["owner"], owner), "unauthorized")
        return owner + ":" + device, entitled, family

    def catalog(self, request: dict, credential: str, dpop: str | None = None) -> dict:
        contract.object_fields(request, contract.BASE_FIELDS)
        context = self.context(request["device_id"])
        contract.bound(request, context)
        principal, entitled, _ = self.authorize(context.device_id, credential, register=True, dpop=dpop)
        key = (self.operator.server / "credentials/server-composite.pub.pem").read_bytes()
        # Keep metadata within strict client bounds across subsecond clock offsets.
        now = int(time.time()) - 1
        services = [{"id": "access", "label": "TCP and UDP access", "role": self.operator.role, "required": ["sealed-kit-1"],
                     "servers": [{"id": "edge", "label": "Synthetic edge", "host": self.operator.host,
                                  "port": self.operator.port, "server_key_sha256": hashlib.sha256(key).hexdigest(), "country": "US"}]}] if entitled else []
        response = {**request, "issued_at": now, "expires_at": now + 300, "services": services}
        with self.database:
            self.database.execute("INSERT OR REPLACE INTO catalogs VALUES (?,?)", (principal, encoded(response)))
        return response

    def grant(self, request: dict, credential: str, dpop: str | None = None) -> dict:
        contract.object_fields(request, contract.REQUEST_FIELDS)
        context = self.context(request["device_id"])
        contract.bound(request, context)
        for key in ("request_id", "service_id", "server_id", "role"):
            contract.identifier(request[key])
        if request["previous_grant"] is not None:
            contract.identifier(request["previous_grant"])
        principal, entitled, family = self.authorize(context.device_id, credential, dpop=dpop, path="/provider/v1/grants")
        contract.require(entitled, "not_entitled")
        existing = self.database.execute("SELECT body FROM requests WHERE principal=? AND request_id=?", (principal, request["request_id"])).fetchone()
        if existing is not None:
            contract.require(existing["body"] == encoded(request), "conflict")
        else:
            catalog = self.database.execute("SELECT payload FROM catalogs WHERE principal=?", (principal,)).fetchone()
            contract.require(catalog is not None, "stale")
            contract.request(request, context, json.loads(catalog["payload"]), int(time.time()))
        result = self.operator.issue(principal, request)
        if family is not None:
            with self.database:
                self.database.execute("INSERT OR IGNORE INTO grant_families VALUES (?,?)", (result["grant_id"], family))
        return result

    def owned(self, grant_id: str, device: str, credential: str, dpop: str | None = None) -> sqlite3.Row:
        principal, _, _ = self.authorize(device, credential, dpop=dpop, method="GET",
                                         path="/provider/v1/grants/" + grant_id + "/kit")
        row = self.operator.row(contract.identifier(grant_id))
        contract.require(row is not None and row["principal"] == principal, "unauthorized")
        return row

    def opening_code(self, request: dict, credential: str, dpop: str | None = None) -> dict:
        contract.object_fields(request, contract.BASE_FIELDS + " grant_id")
        contract.bound(request, self.context(request["device_id"]))
        grant_id = contract.identifier(request["grant_id"])
        principal, entitled, family = self.authorize(request["device_id"], credential, dpop=dpop,
                                                      path="/provider/v1/opening-code")
        contract.require(family is not None, "unsupported")
        row = self.operator.row(grant_id)
        owner = self.database.execute("SELECT family FROM grant_families WHERE grant_id=?", (grant_id,)).fetchone()
        contract.require(row is not None and row["principal"] == principal and owner is not None and
                         hmac.compare_digest(owner["family"], family), "unauthorized")
        contract.require(entitled, "not_entitled")
        contract.require(row["state"] == "active" and time.time() < row["deadline"], "revoked")
        code = (self.operator.root / "handoff" / (grant_id + ".code")).read_text().strip().replace("-", "")
        response = {**request, "opening_code": code}
        contract.opening_code(response, self.context(request["device_id"]), grant_id)
        return response

    def revoke(self, request: dict, credential: str, dpop: str | None = None) -> dict:
        contract.object_fields(request, contract.BASE_FIELDS + " request_id grant_id")
        contract.bound(request, self.context(request["device_id"]))
        contract.identifier(request["request_id"])
        contract.identifier(request["grant_id"])
        principal, _, _ = self.authorize(request["device_id"], credential, dpop=dpop, path="/provider/v1/revoke")
        return self.operator.revoke(principal, request)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *arguments: object) -> None:
        pass

    def reply(self, status: int, payload: bytes, kind: str = "application/json", **headers: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        for key, value in headers.items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        self.dispatch(False)

    def do_POST(self) -> None:
        self.dispatch(True)

    def dispatch(self, post: bool) -> None:
        try:
            self.connection.settimeout(5)
            contract.require(len(self.path) <= 4096)
            contract.require(self.headers.get_all("Host") == [urlsplit(self.server.origin).netloc], "binding")
            contract.require(not self.headers.get_all("Content-Encoding") and not self.headers.get_all("Transfer-Encoding"))
            application = self.server.application
            path = urlsplit(self.path)
            proofs = self.headers.get_all("DPoP") or []
            contract.require(len(proofs) <= 1, "unauthorized")
            dpop = proofs[0] if proofs else None
            raw = b""
            if post:
                lengths = self.headers.get_all("Content-Length")
                contract.require(lengths is not None and len(lengths) == 1 and lengths[0].isdigit())
                length = int(lengths[0])
                contract.integer(length, 1, 16384)
                raw = self.rfile.read(length)
                contract.require(len(raw) == length)
            if isinstance(application, Issuer):
                if not post and path.path == "/authorize":
                    location = application.authorize(form(path.query.encode()), self.headers.get("X-Fixture-Account", "entitled"))
                    if self.server.browser_continue:
                        # A real browser gesture lets Android resolve the verified
                        # callback. No verifier, token or opening code enters HTML.
                        page = ('<!doctype html><title>Fixture authorization</title>'
                                '<a href="' + html.escape(location, quote=True) + '">Return to YUME</a>')
                        self.reply(200, page.encode(), "text/html; charset=utf-8")
                    else:
                        self.reply(302, b"", Location=location)
                    return
                contract.require(post and not path.query and self.headers.get("Content-Type") == "application/x-www-form-urlencoded")
                if path.path == "/token":
                    value = application.tokens(form(raw), dpop)
                elif path.path == "/revoke":
                    value = application.revoke(form(raw))
                else:
                    raise contract.Refusal("malformed")
            else:
                contract.require(not path.query)
                if not post and path.path == "/.well-known/yume-provider":
                    self.reply(200, encoded(application.descriptor()).encode())
                    return
                credentials = self.headers.get_all("Authorization")
                scheme = "DPoP " if application.issuer_origin is not None else "Bearer "
                contract.require(credentials is not None and len(credentials) == 1 and credentials[0].startswith(scheme), "unauthorized")
                credential = credentials[0][len(scheme):]
                contract.string(credential)
                if not post and path.path.startswith("/provider/v1/grants/") and path.path.endswith("/kit"):
                    grant_id = path.path.removeprefix("/provider/v1/grants/").removesuffix("/kit")
                    devices = self.headers.get_all("X-Device-ID")
                    contract.require(devices is not None and len(devices) == 1, "binding")
                    row = application.owned(grant_id, devices[0], credential, dpop)
                    contract.require(row["state"] == "active" and time.time() < row["deadline"], "revoked")
                    self.reply(200, (application.operator.root / "kits" / (grant_id + ".kit")).read_bytes(), "application/octet-stream")
                    return
                contract.require(post and self.headers.get("Content-Type") == "application/json")
                request = contract.decode(raw)
                operations = {"/provider/v1/catalog": application.catalog, "/provider/v1/grants": application.grant,
                              "/provider/v1/revoke": application.revoke, "/provider/v1/opening-code": application.opening_code}
                contract.require(path.path in operations)
                # Fault selection is a private fixture file, never an API field.
                fault = application.operator.root / "fault.json"
                application.operator.fault = None
                application.operator.setup_environment = None
                if fault.exists():
                    options = json.loads(fault.read_bytes())
                    application.operator.fault = options.get("crash")
                    application.operator.setup_environment = options.get("setup_environment")
                value = operations[path.path](request, credential, dpop)
                if fault.exists() and json.loads(fault.read_bytes()).get("lose_reply"):
                    return
            self.reply(200, encoded(value).encode())
        except contract.Refusal as refusal:
            self.reply(STATUS[refusal.code], encoded({"v": 1, "error": refusal.code, "retry_after": 1 if refusal.code in ("rate_limited", "unavailable") else 0}).encode())
        except (RuntimeError, OSError, sqlite3.Error, KeyError, TypeError, ValueError):
            if isinstance(self.server.application, Provider):
                self.server.application.operator.supervisor.call("stop")
            self.reply(503, b'{"v":1,"error":"unavailable","retry_after":1}')


class LoopbackServer(http.server.HTTPServer):
    """Keep even an incomplete TLS handshake bounded on the serialized writer."""

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(2)
        try:
            return self.context.wrap_socket(connection, server_side=True), address
        except BaseException:
            connection.close()
            raise


def serve(arguments: argparse.Namespace) -> None:
    os.umask(0o077)
    private_directory(arguments.root)
    with ExitStack() as cleanup:
        if arguments.kind == "issuer":
            application = Issuer(arguments.root, arguments.origin, arguments.resource,
                                 Registration(arguments.client_id, arguments.redirect_uri))
            cleanup.callback(application.database.close)
        else:
            operator = Operator(arguments.root, arguments.yume, arguments.yumed, arguments.setup,
                                arguments.host, arguments.transport_port, arguments.lease_seconds,
                                arguments.traffic_address, arguments.traffic_dns_address, role=arguments.role or "vpn")
            cleanup.callback(operator.close)
            application = Provider(operator, arguments.origin, arguments.issuer_origin, arguments.issuer_root,
                                   Registration(arguments.client_id, arguments.redirect_uri))
        server = cleanup.enter_context(LoopbackServer(("127.0.0.1", urlsplit(arguments.origin).port), Handler))
        server.timeout = 0.02
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(arguments.certificate, arguments.key)
        server.context = context
        server.application, server.origin = application, arguments.origin
        server.browser_continue = arguments.browser_continue
        worker = None
        reader = None
        if isinstance(application, Provider):
            reader, writer = os.pipe()
            cleanup.callback(os.close, reader)
            try:
                worker = subprocess.Popen([sys.executable, "-c",
                    "import os,sys,time\nfd=int(sys.argv[1])\nwhile True:\n os.write(fd,b'x')\n time.sleep(0.05)", str(writer)],
                    pass_fds=(writer,), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            finally:
                os.close(writer)
            def reap_worker() -> None:
                worker.kill()
                worker.wait(timeout=5)
            cleanup.callback(reap_worker)
        atomic(arguments.root / "ready.json", encoded({"pid": os.getpid(), "worker": worker.pid if worker else None}).encode())
        while True:
            server.handle_request()
            if reader is not None and select.select([reader], [], [], 0)[0]:
                if os.read(reader, 4096):
                    application.operator.sweep()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("issuer", "provider"), required=True)
    for name in ("root", "certificate", "key", "yume", "yumed", "setup", "issuer-root"):
        parser.add_argument("--" + name, type=Path)
    for name in ("origin", "resource", "host", "issuer-origin"):
        parser.add_argument("--" + name)
    parser.add_argument("--client-id", default="synthetic-public-client")
    parser.add_argument("--role", choices=("vpn", "relay"),
                        help="test provider: exact application provisioning role (default: vpn)")
    parser.add_argument("--redirect-uri", default="https://client.example/oauth/callback")
    parser.add_argument("--browser-continue", action="store_true",
                        help="test issuer: show a browser return link instead of an automatic redirect")
    parser.add_argument("--traffic-address",
                        help="test provider: permit this exact non-loopback IPv4 traffic destination")
    parser.add_argument("--traffic-dns-address",
                        help="test provider: also permit this exact IPv4 resolver with --traffic-address")
    parser.add_argument("--transport-port", type=int)
    parser.add_argument("--lease-seconds", type=int, default=90)
    arguments = parser.parse_args()
    needed = ["root", "certificate", "key", "origin"]
    needed += ["resource"] if arguments.kind == "issuer" else ["yume", "yumed", "setup", "host", "transport_port"]
    if any(getattr(arguments, name) is None for name in needed):
        parser.error("required paths and endpoint fields are missing for this service kind")
    if arguments.browser_continue and arguments.kind != "issuer":
        parser.error("--browser-continue is only for the test issuer")
    if arguments.role is not None and arguments.kind != "provider":
        parser.error("--role is only for the test provider")
    if (arguments.traffic_address is not None or arguments.traffic_dns_address is not None) and arguments.kind != "provider":
        parser.error("traffic addresses are only for the test provider")
    if arguments.traffic_dns_address is not None and arguments.traffic_address is None:
        parser.error("--traffic-dns-address requires --traffic-address")
    contract.origin(arguments.origin)
    Registration(arguments.client_id, arguments.redirect_uri)
    if (arguments.issuer_origin is None) != (arguments.issuer_root is None):
        parser.error("issuer origin and private registry must be supplied together")
    serve(arguments)


if __name__ == "__main__":
    main()
