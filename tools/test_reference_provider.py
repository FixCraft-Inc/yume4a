"""Local checks, or real native conformance with explicitly selected binaries."""

from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import shutil
import socket
import socketserver
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlencode, urlsplit

sys.dont_write_bytecode = True
import provider_conformance as contract
from provider_operator import atomic, command, encoded, private_directory
from provider_supervisor import Supervisor
from reference_provider import Issuer, Provider, base64url, form, proof


class FixtureProofKey:
    """Ephemeral host-test P-256 key; Android uses its own Keystore owner."""

    def __init__(self, root: Path) -> None:
        self.path = root / ("proof-" + secrets.token_hex(8) + ".pem")
        command(Path("/usr/bin/openssl"), "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
                "-out", str(self.path))
        public = command(Path("/usr/bin/openssl"), "pkey", "-in", str(self.path), "-pubout", "-outform", "DER").stdout
        assert len(public) == 91 and public[-65] == 4
        self.jwk = {"kty": "EC", "crv": "P-256", "x": base64url(public[-64:-32]), "y": base64url(public[-32:])}

    def create(self, method: str, url: str, access: str | None = None, *, claims: dict | None = None) -> str:
        header = {"typ": "dpop+jwt", "alg": "ES256", "jwk": self.jwk}
        body = {"jti": secrets.token_urlsafe(32), "htm": method, "htu": url, "iat": int(time.time())}
        if access is not None:
            body["ath"] = base64url(hashlib.sha256(access.encode("ascii")).digest())
        if claims:
            body.update(claims)
        message = base64url(encoded(header).encode()) + "." + base64url(encoded(body).encode())
        der = command(Path("/usr/bin/openssl"), "dgst", "-sha256", "-sign", str(self.path), data=message.encode("ascii")).stdout
        assert der[0] == 0x30 and der[1] == len(der) - 2
        at, signature = 2, b""
        for _ in range(2):
            assert der[at] == 2
            length = der[at + 1]
            half = der[at + 2:at + 2 + length]
            assert 1 <= length <= 33 and len(half) == length
            signature += int.from_bytes(half, "big").to_bytes(32, "big")
            at += 2 + length
        assert at == len(der)
        return message + "." + base64url(signature)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait(predicate, seconds: float, description: str) -> None:
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError(description)


class LocalChecks(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.key = FixtureProofKey(Path(self.temporary.name))

    def token_proof(self, issuer: Issuer) -> str:
        return self.key.create("POST", issuer.origin + "/token")

    def test_proof_signature_claims_and_replay(self) -> None:
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("CREATE TABLE proofs (thumbprint TEXT, jti TEXT, until INTEGER, PRIMARY KEY(thumbprint,jti))")
        url, access = "https://alpha.example/provider/v1/catalog", "SYNTHETIC-TOKEN"
        good = self.key.create("POST", url, access)
        thumbprint = proof(db, good, "POST", url, access)
        self.assertEqual(len(thumbprint), 43)
        with self.assertRaises(contract.Refusal):
            proof(db, good, "POST", url, access)
        for claims in ({"htm": "GET"}, {"htu": "https://beta.example/provider/v1/catalog"},
                       {"iat": int(time.time()) - 61}, {"iat": int(time.time()) + 61},
                       {"iat": True}, {"ath": "other"}, {"jti": "short"}, {"extra": "field"}):
            with self.subTest(claims=tuple(claims)), self.assertRaises(contract.Refusal):
                proof(db, self.key.create("POST", url, access, claims=claims), "POST", url, access)
        signature = self.key.create("POST", url, access).rsplit(".", 1)
        signature[1] = base64url(b"\0" * 64)
        for invalid in (None, "a" * 2049, "x.y", ".".join(signature)):
            with self.assertRaises(contract.Refusal):
                proof(db, invalid, "POST", url, access)
        # Failed checks never consume a fresh jti or publish a key binding.
        self.assertEqual(db.execute("SELECT count(*) FROM proofs").fetchone()[0], 1)

    def test_missing_proof_does_not_consume_code_and_wrong_refresh_key_revokes_family(self) -> None:
        root = Path(self.temporary.name)
        issuer = Issuer(root, "https://login.alpha.example", "https://alpha.example/provider/v1")
        self.addCleanup(issuer.database.close)
        request, verifier = authorization(issuer.resource)
        code = parse_qs(urlsplit(issuer.authorize(request, "entitled")).query)["code"][0]
        exchange = exchange_request(code, verifier, issuer.resource)
        with self.assertRaises(contract.Refusal):
            issuer.tokens(exchange)
        tokens = issuer.tokens(exchange, self.token_proof(issuer))
        self.assertEqual(tokens["token_type"], "DPoP")
        refresh = {"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"],
                   "client_id": "synthetic-public-client", "resource": issuer.resource}
        other = FixtureProofKey(root)
        with self.assertRaises(contract.Refusal):
            issuer.tokens(refresh, other.create("POST", issuer.origin + "/token"))
        with self.assertRaises(contract.Refusal):
            issuer.tokens(refresh, self.token_proof(issuer))
        self.assertEqual(issuer.database.execute("SELECT count(*) FROM revoked").fetchone()[0], 1)

    def test_delivered_code_is_family_device_and_active_grant_bound(self) -> None:
        from types import SimpleNamespace
        root = Path(self.temporary.name)
        issuer_root, provider_root = root / "issuer", root / "provider"
        issuer_root.mkdir()
        provider_root.mkdir()
        (provider_root / "handoff").mkdir()
        issuer = Issuer(issuer_root, "https://login.alpha.example", "https://alpha.example/provider/v1")
        self.addCleanup(issuer.database.close)
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        self.addCleanup(db.close)
        db.execute("CREATE TABLE principals (device TEXT PRIMARY KEY, credential TEXT, expires INTEGER, owner TEXT)")
        row = {"principal": "entitled:device-one", "state": "active", "deadline": int(time.time()) + 60}
        operator = SimpleNamespace(database=db, root=provider_root, role="vpn", row=lambda grant: row if grant == "grant-one" else None)
        provider = Provider(operator, "https://alpha.example", issuer.origin, issuer_root)
        def login() -> dict:
            auth, verifier = authorization(issuer.resource)
            code = parse_qs(urlsplit(issuer.authorize(auth, "entitled")).query)["code"][0]
            return issuer.tokens(exchange_request(code, verifier, issuer.resource), self.token_proof(issuer))
        token = login()
        access = token["access_token"]
        family = issuer.database.execute("SELECT family FROM tokens WHERE kind='access' AND hash=?", (hashlib.sha256(access.encode()).hexdigest(),)).fetchone()[0]
        db.execute("INSERT INTO grant_families VALUES (?,?)", ("grant-one", family))
        db.execute("INSERT INTO principals VALUES (?,?,?,?)", ("device-one", "unused", int(time.time()) + 60, "entitled"))
        db.commit()
        # Native --seal-kit emits five groups separated by hyphens.
        (provider_root / "handoff/grant-one.code").write_text("-".join(["0" * 5] * 5) + "\n")
        request = {"v": 1, "provider": provider.origin, "device_id": "device-one", "audience": issuer.resource, "grant_id": "grant-one"}
        url = provider.origin + "/provider/v1/opening-code"
        response = provider.opening_code(request, access, self.key.create("POST", url, access))
        self.assertEqual(response["opening_code"], "0" * 25)
        for change in ({"grant_id": "unknown"}, {"device_id": "device-two"}):
            with self.assertRaises(contract.Refusal):
                provider.opening_code({**request, **change}, access, self.key.create("POST", url, access))
        foreign = login()["access_token"]
        with self.assertRaises(contract.Refusal):
            provider.opening_code(request, foreign, self.key.create("POST", url, foreign))
        other = FixtureProofKey(root)
        with self.assertRaises(contract.Refusal):
            provider.opening_code(request, access, other.create("POST", url, access))
        row["state"] = "revoked"
        with self.assertRaises(contract.Refusal):
            provider.opening_code(request, access, self.key.create("POST", url, access))

    def test_form_rejects_duplicates_and_oversize(self) -> None:
        for raw in (b"state=a&state=b", b"a=" + b"x" * 16384, b"\xff", b"broken"):
            with self.assertRaises(contract.Refusal):
                form(raw)

    def test_issuer_binds_s256_resource_and_callback(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            issuer = Issuer(Path(name), "https://login.alpha.example", "https://alpha.example/provider/v1")
            try:
                request = authorization("https://alpha.example/provider/v1")
                for key, value in (("resource", "https://beta.example/provider/v1"),
                                   ("redirect_uri", "https://client.example/other"),
                                   ("code_challenge_method", "plain"), ("client_id", "secret-client")):
                    with self.subTest(key=key), self.assertRaises(contract.Refusal):
                        issuer.authorize({**request[0], key: value}, "entitled")
                location = issuer.authorize(request[0], "entitled")
                query = {k: v[0] for k, v in parse_qs(urlsplit(location).query).items()}
                self.assertEqual(query["iss"], issuer.origin)
                self.assertEqual(query["state"], request[0]["state"])
                exchange = exchange_request(query["code"], request[1], issuer.resource)
                with self.assertRaises(contract.Refusal):
                    issuer.tokens({**exchange, "code_verifier": secrets.token_urlsafe(32)}, self.token_proof(issuer))
                with self.assertRaises(contract.Refusal):
                    issuer.tokens(exchange, self.token_proof(issuer))
            finally:
                issuer.database.close()

    def test_refresh_reuse_revokes_family(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            issuer = Issuer(Path(name), "https://login.alpha.example", "https://alpha.example/provider/v1")
            try:
                request, verifier = authorization(issuer.resource)
                location = issuer.authorize(request, "entitled")
                code = parse_qs(urlsplit(location).query)["code"][0]
                tokens = issuer.tokens(exchange_request(code, verifier, issuer.resource), self.token_proof(issuer))
                refresh = {"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"],
                           "client_id": "synthetic-public-client", "resource": issuer.resource}
                replacement = issuer.tokens(refresh, self.token_proof(issuer))
                self.assertNotEqual(tokens["refresh_token"], replacement["refresh_token"])
                with self.assertRaises(contract.Refusal):
                    issuer.tokens(refresh, self.token_proof(issuer))
                with self.assertRaises(contract.Refusal):
                    issuer.tokens({**refresh, "refresh_token": replacement["refresh_token"]}, self.token_proof(issuer))
            finally:
                issuer.database.close()


def authorization(resource: str) -> tuple[dict, str]:
    verifier = secrets.token_urlsafe(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return {"response_type": "code", "client_id": "synthetic-public-client",
            "redirect_uri": "https://client.example/oauth/callback", "scope": "provider.enroll",
            "state": secrets.token_urlsafe(32), "code_challenge": challenge,
            "code_challenge_method": "S256", "resource": resource}, verifier


def exchange_request(code: str, verifier: str, resource: str) -> dict:
    return {"grant_type": "authorization_code", "code": code, "client_id": "synthetic-public-client",
            "redirect_uri": "https://client.example/oauth/callback", "code_verifier": verifier, "resource": resource}


class LoopbackHTTPS(http.client.HTTPSConnection):
    def connect(self) -> None:
        raw = socket.create_connection(("127.0.0.1", self.port), timeout=self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


class Echo(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(5)
        try:
            while data := self.request.recv(4096):
                self.request.sendall(data)
        except (OSError, TimeoutError):
            return


class EchoServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    block_on_close = False


class Fixture:
    def __init__(self, root: Path, arguments: argparse.Namespace) -> None:
        self.root, self.arguments = root, arguments
        self.kind = arguments.operator or "beta"
        self.role = arguments.role
        self.processes: list[subprocess.Popen] = []
        self.clients: list[subprocess.Popen] = []
        self.origins = {"alpha": "https://alpha.example:" + str(port()),
                        "beta": "https://beta.example:" + str(port()),
                        "issuer": "https://login.alpha.example:" + str(port())}
        self.transport = {kind: port() for kind in ("alpha", "beta", "manual")}
        self.credentials: dict[str, str] = {}
        self.catalogs: dict[str, dict] = {}
        self.grants: dict[str, dict] = {}
        self.echo = EchoServer(("127.0.0.1", 0), Echo)
        self.echo_thread = threading.Thread(target=self.echo.serve_forever, daemon=True)
        self.echo_thread.start()
        self.manual: Supervisor | None = None
        self.proof_key = FixtureProofKey(root)

    def start(self) -> None:
        for name in ("alpha", "beta", "issuer", "manual", "clients", "tls"):
            (self.root / name).mkdir(mode=0o700)
        tls = self.root / "tls"
        command(Path("/usr/bin/openssl"), "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                "-nodes", "-keyout", str(tls / "ca.key"), "-out", str(tls / "ca.pem"), "-days", "1", "-subj", "/CN=Temporary fixture CA",
                "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        command(Path("/usr/bin/openssl"), "req", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                "-keyout", str(tls / "server.key"), "-out", str(tls / "request.pem"), "-subj", "/CN=alpha.example")
        (tls / "extensions").write_text("subjectAltName=DNS:alpha.example,DNS:beta.example,DNS:login.alpha.example\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n")
        command(Path("/usr/bin/openssl"), "x509", "-req", "-in", str(tls / "request.pem"), "-CA", str(tls / "ca.pem"),
                "-CAkey", str(tls / "ca.key"), "-CAcreateserial", "-out", str(tls / "server.pem"), "-days", "1", "-extfile", str(tls / "extensions"))
        self.tls = ssl.create_default_context(cafile=str(tls / "ca.pem"))
        for kind in ("issuer", "alpha", "beta"):
            self.launch(kind)

    def launch(self, kind: str, lease: int = 100, *, rejected: bool = False, role: str | None = None) -> None:
        root = self.root / kind
        (root / "ready.json").unlink(missing_ok=True)
        args = [sys.executable, str(Path(__file__).with_name("reference_provider.py")), "--kind", "issuer" if kind == "issuer" else "provider",
                "--root", str(root), "--origin", self.origins[kind], "--certificate", str(self.root / "tls/server.pem"),
                "--key", str(self.root / "tls/server.key")]
        if kind == "issuer":
            args += ["--resource", self.origins["alpha"] + "/provider/v1"]
        else:
            args += ["--yume", str(self.arguments.yume), "--yumed", str(self.arguments.yumed), "--setup", str(self.arguments.setup),
                     "--host", "edge." + kind + ".example", "--transport-port", str(self.transport[kind]), "--lease-seconds", str(lease),
                     "--role", role or self.role]
            if kind == "alpha":
                args += ["--issuer-origin", self.origins["issuer"], "--issuer-root", str(self.root / "issuer")]
        with (root / "service.log").open("ab") as log:
            process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        self.processes.append(process)
        wait(lambda: (root / "ready.json").exists() or process.poll() is not None, 15, "service readiness timeout")
        if rejected:
            assert process.poll() is not None and process.wait(timeout=5) != 0, "unsafe restart was accepted"
            assert not (root / "ready.json").exists(), "unsafe restart published readiness"
            return
        assert process.poll() is None, "service failed before readiness"
        self.request(kind, "/.well-known/yume-provider") if kind != "issuer" else None

    def process(self, kind: str) -> subprocess.Popen:
        pid = json.loads((self.root / kind / "ready.json").read_bytes())["pid"]
        return next(process for process in self.processes if process.pid == pid)

    def request(self, kind: str, path: str, body: dict | None = None, *, token: str | None = None,
                status: int = 200, form_body: bool = False, headers: dict | None = None) -> tuple[dict | bytes, dict]:
        origin = urlsplit(self.origins[kind])
        connection = LoopbackHTTPS(origin.hostname, origin.port, context=self.tls, timeout=10)
        selected = {"Content-Type": "application/x-www-form-urlencoded" if form_body else "application/json"}
        if headers:
            selected.update(headers)
        if token is not None:
            selected["Authorization"] = ("DPoP " if kind == "alpha" else "Bearer ") + token
        if kind == "alpha" and token is not None:
            selected["DPoP"] = self.proof_key.create("POST" if body is not None else "GET", self.origins[kind] + path, token)
        if kind == "issuer" and path == "/token":
            selected["DPoP"] = self.proof_key.create("POST", self.origins[kind] + path)
        payload = (urlencode(body).encode() if form_body else encoded(body).encode()) if body is not None else None
        try:
            connection.request("POST" if body is not None else "GET", path, payload, selected)
            response = connection.getresponse()
            raw = response.read(1049649)
            assert response.status == status, f"unexpected HTTP status {response.status}, expected {status}"
            assert response.getheader("Cache-Control") == "no-store"
            assert response.getheader("Set-Cookie") is None and response.getheader("Content-Encoding") is None
            result = contract.decode(raw, 65536) if raw and response.getheader("Content-Type") == "application/json" else raw
            return result, dict(response.getheaders())
        finally:
            connection.close()

    def login(self, *, subject: str = "entitled") -> dict:
        resource = self.origins["alpha"] + "/provider/v1"
        request, verifier = authorization(resource)
        _, headers = self.request("issuer", "/authorize?" + urlencode(request), status=302, headers={"X-Fixture-Account": subject})
        redirect = urlsplit(headers["Location"])
        assert redirect.scheme + "://" + redirect.netloc + redirect.path == request["redirect_uri"]
        callback = {k: v[0] for k, v in parse_qs(redirect.query).items()}
        assert callback["state"] == request["state"] and callback["iss"] == self.origins["issuer"]
        exchange = exchange_request(callback["code"], verifier, resource)
        tokens, _ = self.request("issuer", "/token", exchange, form_body=True)
        contract.token_pair(tokens, int(time.time()))
        self.request("issuer", "/token", exchange, form_body=True, status=401)
        return tokens

    def enroll(self, kind: str, number: int, *, token: str | None = None) -> str:
        device = kind + "-device-" + str(number)
        credential = token if token is not None else secrets.token_urlsafe(32)
        self.credentials[device] = credential
        body = self.base(kind, device)
        catalog, _ = self.request(kind, "/provider/v1/catalog", body, token=credential)
        descriptor, _ = self.request(kind, "/.well-known/yume-provider")
        contract.catalog(catalog, descriptor, self.context(kind, device), int(time.time()))
        self.catalogs[device] = catalog
        return device

    def context(self, kind: str, device: str) -> contract.Context:
        return contract.Context(self.origins[kind], device, self.role, "synthetic-public-client",
                                "https://client.example/oauth/callback", self.origins["issuer"] if kind == "alpha" else None)

    def base(self, kind: str, device: str) -> dict:
        return {"v": 1, "provider": self.origins[kind], "device_id": device, "audience": self.origins[kind] + "/provider/v1"}

    def issuance(self, kind: str, device: str, *, previous: dict | None = None, request_id: str | None = None) -> tuple[dict, dict]:
        request = {**self.base(kind, device), "request_id": request_id or secrets.token_hex(16), "service_id": "access",
                   "server_id": "edge", "role": self.role, "previous_grant": previous["grant_id"] if previous else None}
        response, _ = self.request(kind, "/provider/v1/grants", request, token=self.credentials[device])
        contract.grant(response, request, previous, int(time.time()))
        self.grants[device] = response
        return request, response

    def open_kit(self, kind: str, device: str, grant: dict) -> Path:
        payload, _ = self.request(kind, "/provider/v1/grants/" + grant["grant_id"] + "/kit", token=self.credentials[device],
                                  headers={"X-Device-ID": device})
        assert len(payload) == grant["kit_size"] and hashlib.sha256(payload).hexdigest() == grant["kit_sha256"]
        name = secrets.token_hex(5)
        sealed = self.root / "clients" / (name + ".kit")
        sealed.write_bytes(payload)
        bundle = self.root / "clients" / name
        if kind == "alpha":
            delivered, _ = self.request(kind, "/provider/v1/opening-code", {**self.base(kind, device), "grant_id": grant["grant_id"]},
                                         token=self.credentials[device])
            code = contract.opening_code(delivered, self.context(kind, device), grant["grant_id"]).encode("ascii")
        else:
            code = (self.root / kind / "handoff" / (grant["grant_id"] + ".code")).read_bytes()
        command(self.arguments.yume, "--import-kit", str(sealed), "--into", str(bundle), data=code)
        config = json.loads((bundle / "yume.json").read_bytes())
        assert config["role"] == "client"
        selected = self.catalogs[device]["services"][0]["servers"][0]
        assert config["endpoint"]["host"] == selected["host"] and config["endpoint"]["port"] == selected["port"]
        for reference in config["credentials"].values():
            relative = Path(reference["file"])
            assert not relative.is_absolute() and ".." not in relative.parts
            assert all(not (bundle / Path(*relative.parts[:i])).is_symlink() for i in range(1, len(relative.parts)+1))
            assert (bundle / relative).is_file()
        assert sha(bundle / config["credentials"]["server_identity"]["file"]) == selected["server_key_sha256"]
        with sqlite3.connect(self.root / kind / "journal.sqlite") as db:
            result = db.execute("SELECT result FROM requests WHERE grant_id=?", (grant["grant_id"],)).fetchone()[0]
        assert json.loads(result)["client_identity"] == grant["client_identity"]
        command(self.arguments.yume, "--config", str(bundle / "yume.json"), "--validate")
        return bundle

    @contextmanager
    def traffic(self, bundle: Path, *, expected: bool = True):
        config = json.loads((bundle / "yume.json").read_bytes())
        config["endpoint"]["connect_address"] = "127.0.0.1"
        listener = port()
        config["adapters"] = [{"kind": "forward", "service": "tcp", "listen_address": "127.0.0.1", "listen_port": listener,
                               "destination": {"host": "127.0.0.1", "port": self.echo.server_address[1]}}]
        atomic(bundle / "yume.json", encoded(config).encode())
        with (bundle / "traffic.log").open("ab") as log:
            process = subprocess.Popen([str(self.arguments.yume), "--config", str(bundle / "yume.json")],
                                       stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
        self.clients.append(process)
        connection = None
        try:
            end = time.monotonic() + (8 if expected else 3)
            while time.monotonic() < end and process.poll() is None:
                try:
                    connection = socket.create_connection(("127.0.0.1", listener), timeout=0.2)
                    connection.settimeout(1)
                    if self.echoed(connection):
                        break
                    connection.close()
                    connection = None
                except OSError:
                    if connection is not None:
                        connection.close()
                        connection = None
                time.sleep(0.03)
            if expected:
                assert connection is not None, "native admission/traffic failed"
                assert self.echoed(connection), "established traffic failed"
            else:
                assert connection is None, "unauthorized native admission succeeded"
            yield connection
        finally:
            if connection is not None:
                connection.close()
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)

    @staticmethod
    def echoed(connection: socket.socket) -> bool:
        payload = secrets.token_bytes(32)
        try:
            connection.sendall(payload)
            received = b""
            while len(received) < len(payload):
                chunk = connection.recv(len(payload)-len(received))
                if not chunk:
                    return False
                received += chunk
            return received == payload
        except OSError:
            return False

    def revoked(self, kind: str, device: str, grant: dict) -> dict:
        request = {**self.base(kind, device), "request_id": secrets.token_hex(16), "grant_id": grant["grant_id"]}
        response, _ = self.request(kind, "/provider/v1/revoke", request, token=self.credentials[device])
        assert response == {**request, "status": "revoked"}
        return request

    def close(self) -> None:
        if self.manual is not None:
            self.manual.close()
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=8)
        for process in self.clients:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        self.echo.shutdown()
        self.echo.server_close()
        self.echo_thread.join(timeout=5)


def bindings(fixture: Fixture, results: dict) -> None:
    tokens = fixture.login()
    # Exercise real token rotation and family-reuse rejection over verified TLS.
    rotation = fixture.login()
    request = {"grant_type": "refresh_token", "refresh_token": rotation["refresh_token"],
               "client_id": "synthetic-public-client", "resource": fixture.origins["alpha"] + "/provider/v1"}
    rotated, _ = fixture.request("issuer", "/token", request, form_body=True)
    assert rotated["refresh_token"] != rotation["refresh_token"]
    fixture.request("issuer", "/token", request, form_body=True, status=401)
    fixture.request("alpha", "/provider/v1/catalog", fixture.base("alpha", "reused-family"), token=rotated["access_token"], status=401)
    a1 = fixture.enroll("alpha", 1, token=tokens["access_token"])
    a2 = fixture.enroll("alpha", 2, token=tokens["access_token"])
    denied = fixture.login(subject="denied")
    no = fixture.enroll("alpha", 3, token=denied["access_token"])
    assert fixture.catalogs[no]["services"] == []
    b1 = fixture.enroll("beta", 1)
    b2 = fixture.enroll("beta", 2)
    issued = {}
    for kind, device in (("alpha", a1), ("alpha", a2), ("beta", b1), ("beta", b2)):
        descriptor, _ = fixture.request(kind, "/.well-known/yume-provider")
        assert descriptor["roles"] == [fixture.role]
        assert fixture.catalogs[device]["services"][0]["role"] == fixture.role
        request, grant = fixture.issuance(kind, device)
        duplicate, _ = fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
        assert duplicate == grant, "duplicate issuance changed grant"
        fixture.request(kind, "/provider/v1/grants", {**request, "service_id": "other"}, token=fixture.credentials[device], status=409)
        wrong_role = "relay" if fixture.role == "vpn" else "vpn"
        fixture.request(kind, "/provider/v1/grants", {**request, "request_id": "wrong-role", "role": wrong_role},
                        token=fixture.credentials[device], status=422)
        fixture.request(kind, "/provider/v1/grants", {**request, "role": wrong_role},
                        token=fixture.credentials[device], status=409)
        fixture.request(kind, "/provider/v1/grants", {**request, "request_id": "wrong-role-rotation", "role": wrong_role,
                        "previous_grant": grant["grant_id"]}, token=fixture.credentials[device], status=422)
        bundle = fixture.open_kit(kind, device, grant)
        with fixture.traffic(bundle):
            pass
        issued[device] = (request, grant, bundle)
    assert len({value[1]["client_identity"] for value in issued.values()}) == 4
    r = issued[b1][0]
    for changed in ({**r, "provider": fixture.origins["alpha"]}, {**r, "audience": fixture.origins["alpha"] + "/provider/v1"}):
        fixture.request("beta", "/provider/v1/grants", changed, token=fixture.credentials[b1], status=400)
    fixture.request("beta", "/provider/v1/catalog", fixture.base("beta", b1), token=secrets.token_urlsafe(32), status=401)
    fixture.request("beta", "/provider/v1/grants", {**r, "device_id": b2}, token=fixture.credentials[b1], status=401)
    fixture.request("beta", "/provider/v1/catalog", fixture.base("beta", "third-device"), token=secrets.token_urlsafe(32), status=429)
    fixture.request("beta", "/provider/v1/catalog", fixture.base("beta", b1), token=tokens["access_token"], status=401)
    bad = {**r, "device_id": b2, "request_id": "wrong-service", "service_id": "not-listed"}
    fixture.request("beta", "/provider/v1/grants", bad, token=fixture.credentials[b2], status=403)
    bad = {**issued[a1][0], "device_id": no, "request_id": "denied"}
    fixture.request("alpha", "/provider/v1/grants", bad, token=fixture.credentials[no], status=403)
    ar = issued[a1][0]
    for changed in ({**ar, "provider": fixture.origins["beta"]}, {**ar, "audience": fixture.origins["beta"] + "/provider/v1"}):
        fixture.request("alpha", "/provider/v1/grants", changed, token=fixture.credentials[a1], status=400)
    fixture.request("alpha", "/provider/v1/grants", {**ar, "device_id": no}, token=fixture.credentials[a1], status=401)
    fixture.request("alpha", "/provider/v1/catalog", fixture.base("alpha", a1), token=fixture.credentials[b1], status=401)
    fixture.request("alpha", "/provider/v1/catalog", fixture.base("alpha", "fourth-device"), token=fixture.credentials[a1], status=429)
    fixture.request("alpha", "/provider/v1/grants", {**ar, "request_id": "wrong-service", "service_id": "not-listed"},
                    token=fixture.credentials[a1], status=403)
    for kind in ("alpha", "beta"):
        store = fixture.root / kind / "initial/server/credentials/authorized-keys.json"
        assert len(json.loads(store.read_bytes())["keys"]) == 3, "refusal allocated native authority"
        with sqlite3.connect(fixture.root / kind / "journal.sqlite") as db:
            assert db.execute("SELECT count(*) FROM requests").fetchone()[0] == 2, "refusal allocated a grant"
    fixture.request("beta", "/provider/v1/grants/" + issued[b1][1]["grant_id"] + "/kit", token=fixture.credentials[b2], headers={"X-Device-ID": b2}, status=401)
    for kind, device, foreign in (("alpha", a1, b1), ("beta", b1, a1)):
        good = issued[device][2]
        bad_bundle = fixture.root / "clients" / (kind + "-bad-credential")
        shutil.copytree(good, bad_bundle)
        access = bad_bundle / "credentials/client-access.psk"
        value = bytearray(access.read_bytes())
        value[0] ^= 1
        access.write_bytes(value)
        with fixture.traffic(bad_bundle, expected=False):
            pass
        unknown = fixture.root / "clients" / (kind + "-unknown-identity")
        shutil.copytree(good, unknown)
        for credential in ("client-composite.pem", "client-composite.pub.pem", "client-access.psk"):
            shutil.copyfile(issued[foreign][2] / "credentials" / credential, unknown / "credentials" / credential)
        # Valid trust/admission material for this server, but no entitled native identity.
        command(fixture.arguments.yume, "--config", str(unknown / "yume.json"), "--validate")
        with fixture.traffic(unknown, expected=False):
            pass
        wrong_server = fixture.root / "clients" / (kind + "-wrong-server")
        shutil.copytree(good, wrong_server)
        config = json.loads((wrong_server / "yume.json").read_bytes())
        config["endpoint"]["port"] = fixture.transport["beta" if kind == "alpha" else "alpha"]
        atomic(wrong_server / "yume.json", encoded(config).encode())
        with fixture.traffic(wrong_server, expected=False):
            pass
    results["bindings"] = {"devices": 4, "independent_native_identities": 4, "actual_kit_metadata_and_native_validation": True,
                           "role": fixture.role, "wrong_role_new_retry_and_rotation_refused": True}


def revocation(fixture: Fixture, results: dict) -> None:
    kind = fixture.kind
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    issuance, grant = fixture.issuance(kind, device)
    bundle = fixture.open_kit(kind, device, grant)
    with fixture.traffic(bundle) as connection:
        revoke = fixture.revoked(kind, device, grant)
        wait(lambda: not fixture.echoed(connection), 2, "revoked identity retained established traffic")
    fixture.request(kind, "/provider/v1/revoke", revoke, token=fixture.credentials[device])
    _, another = fixture.issuance(kind, device)
    fixture.request(kind, "/provider/v1/revoke", {**revoke, "grant_id": another["grant_id"]}, token=fixture.credentials[device], status=409)
    fixture.request(kind, "/provider/v1/revoke", {**revoke, "request_id": issuance["request_id"]}, token=fixture.credentials[device], status=409)
    fixture.request(kind, "/provider/v1/grants", {**fixture.base(kind, device), "request_id": revoke["request_id"],
                    "service_id": "access", "server_id": "edge", "role": fixture.role, "previous_grant": None},
                    token=fixture.credentials[device], status=409)
    with fixture.traffic(bundle, expected=False):
        pass
    results["revocation"] = {"established_traffic_closed": True, "readmission_refused": True, "idempotent_revoke": True}


def restart_safety(fixture: Fixture, results: dict) -> None:
    kind = fixture.kind
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    _, grant = fixture.issuance(kind, device)
    bundle = fixture.open_kit(kind, device, grant)
    with fixture.traffic(bundle) as connection:
        fixture.process(kind).terminate()
        fixture.process(kind).wait(timeout=5)
        wait(lambda: not fixture.echoed(connection), 2, "provider termination retained established traffic")
    role_before = (fixture.root / kind / "journal.sqlite").read_bytes()
    fixture.launch(kind, rejected=True, role="relay" if fixture.role == "vpn" else "vpn")
    assert (fixture.root / kind / "journal.sqlite").read_bytes() == role_before, "role mismatch modified journal"
    assert refused_port(fixture.transport[kind]), "role mismatch started a daemon"
    server = fixture.root / kind / "initial/server"
    command(fixture.arguments.setup, "add-client", "--server", str(server), "--host", "edge." + kind + ".example",
            "--client-name", "unjournaled", "--output", str(fixture.root / kind / "rogue"))
    fixture.launch(kind, rejected=True)
    assert refused_port(fixture.transport[kind]), "unjournaled authority admitted traffic"
    command(fixture.arguments.setup, "remove-client", "--server", str(server), "--client-name", "unjournaled")
    fixture.launch(kind)
    with fixture.traffic(bundle):
        pass
    results["restart_safety"] = {"provider_death_closed_traffic": True, "unjournaled_authority_refused_before_start": True,
                                 "repaired_journaled_identity_works": True, "changed_role_refused_before_start": True}


def reload_safety(fixture: Fixture, results: dict) -> None:
    # A separate actual daemon exercises the supervisor's refused and absent outcomes.
    root = fixture.root / "manual"
    command(fixture.arguments.setup, "init", "--host", "offline.example", "--port", str(fixture.transport["manual"]),
            "--output", str(root / "initial"), "--client-name", "offline")
    server = root / "initial/server"
    settings = json.loads((server / "yumed.json").read_bytes())
    settings["endpoint"]["listen_addresses"] = ["127.0.0.1"]
    for adapter in settings["adapters"]:
        adapter["destinations"] = {"public": False, "networks": ["127.0.0.0/8"]}
    atomic(server / "yumed.json", encoded(settings).encode())
    supervisor = Supervisor(fixture.arguments.yumed, server / "yumed.json", root / "daemon.log")
    fixture.manual = supervisor
    supervisor.call("start")
    bundle = root / "initial/client"
    store = server / "credentials/authorized-keys.json"
    original = store.read_bytes()
    with fixture.traffic(bundle) as connection:
        # A previous successful line must not acknowledge this refused reload.
        supervisor.call("reload")
        atomic(store, b"{invalid")
        try:
            supervisor.call("reload")
            raise AssertionError("refused reload was acknowledged")
        except RuntimeError:
            pass
        wait(lambda: not fixture.echoed(connection), 2, "refused reload left traffic active")
    assert refused_port(fixture.transport["manual"]), "refused reload left daemon listening"
    atomic(store, original)
    pid = supervisor.call("start")["pid"]
    with fixture.traffic(bundle) as connection:
        os.kill(pid, signal.SIGSTOP)
        try:
            supervisor.call("reload")
            raise AssertionError("missing reload was acknowledged")
        except RuntimeError:
            pass
        wait(lambda: not fixture.echoed(connection), 2, "missing reload left traffic active")
    assert refused_port(fixture.transport["manual"]), "missing reload left daemon listening"
    results["reload_safety"] = {"refused_outcome_closed_traffic": True, "missing_outcome_closed_traffic": True,
                                "stale_success_not_accepted": True}


def refresh(fixture: Fixture, results: dict) -> None:
    kind = fixture.kind
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    _, old = fixture.issuance(kind, device)
    bundle = fixture.open_kit(kind, device, old)
    with fixture.traffic(bundle) as connection:
        request, grant = fixture.issuance(kind, device, previous=old)
        wait(lambda: not fixture.echoed(connection), 2, "refresh retained old established traffic")
    recovered, _ = fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
    assert recovered == grant
    with fixture.traffic(bundle, expected=False):
        pass
    replacement = fixture.open_kit(kind, device, grant)
    with fixture.traffic(replacement):
        pass
    results["refresh"] = {"old_traffic_closed_and_admission_refused": True, "new_identity_works": True, "retry_original_expiry": True}


def publication(fixture: Fixture, results: dict) -> None:
    kind = fixture.kind
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    fault = fixture.root / kind / "fault.json"
    atomic(fault, encoded({"lose_reply": True}).encode())
    request = {**fixture.base(kind, device), "request_id": "lost-reply", "service_id": "access", "server_id": "edge", "role": fixture.role, "previous_grant": None}
    try:
        fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
        raise AssertionError("lost reply was not lost")
    except http.client.RemoteDisconnected:
        pass
    fault.unlink()
    grant, _ = fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
    with sqlite3.connect(fixture.root / kind / "journal.sqlite") as db:
        assert db.execute("SELECT count(*) FROM requests").fetchone()[0] == 1, "lost reply minted duplicates"
    bundle = fixture.open_kit(kind, device, grant)
    with fixture.traffic(bundle):
        pass
    # Real LD_PRELOAD failure after native store rename, not an injected HTTP result.
    shim = fixture.arguments.source_root / "tests/fixtures/fsync_after_rename_shim.c"
    library = fixture.root / "fsync.so"
    command(Path("/usr/bin/cc"), "-shared", "-fPIC", "-o", str(library), str(shim), "-ldl")
    environment = dict(os.environ, LD_PRELOAD=str(library), YUME_FSYNC_FAILURE_TARGET="authorized-keys.json")
    atomic(fault, encoded({"setup_environment": environment}).encode())
    _, partial = fixture.issuance(kind, device, request_id="partial-publication")
    with sqlite3.connect(fixture.root / kind / "journal.sqlite") as db:
        assert db.execute("SELECT outcome FROM events WHERE grant_id=? AND operation='add-client'", (partial["grant_id"],)).fetchone()[0] == 3, "partial publication did not execute native exit 3"
    fault.unlink()
    partial_bundle = fixture.open_kit(kind, device, partial)
    with fixture.traffic(partial_bundle):
        pass
    results["publication"] = {"real_partial_publication": True, "lost_reply_one_journal_identity": True}


def crashes(fixture: Fixture, results: dict) -> None:
    kind = fixture.kind
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    points = ("after_journal", "after_add", "after_seal", "after_result", "after_remove")
    for point in points:
        request = {**fixture.base(kind, device), "request_id": point, "service_id": "access", "server_id": "edge", "role": fixture.role, "previous_grant": None}
        fault = fixture.root / kind / "fault.json"
        if point == "after_remove":
            _, grant = fixture.issuance(kind, device, request_id=point)
            bundle = fixture.open_kit(kind, device, grant)
        atomic(fault, encoded({"crash": point}).encode())
        try:
            if point == "after_remove":
                with fixture.traffic(bundle) as connection:
                    try:
                        fixture.revoked(kind, device, grant)
                    except http.client.RemoteDisconnected:
                        pass
                    wait(lambda: not fixture.echoed(connection), 2, "provider crash retained traffic")
            else:
                fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
        except http.client.RemoteDisconnected:
            pass
        old_process = fixture.process(kind)
        assert old_process.wait(timeout=5) == 91, "crash point was not executed"
        wait(lambda: refused_port(fixture.transport[kind]), 3, "crashed provider left daemon serving")
        fault.unlink()
        fixture.launch(kind)
        if point == "after_journal":
            fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device], status=410)
        elif point == "after_remove":
            with fixture.traffic(bundle, expected=False):
                pass
        else:
            first, _ = fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
            second, _ = fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device])
            assert first == second, "restart minted another grant"
            bundle = fixture.open_kit(kind, device, first)
            with fixture.traffic(bundle):
                pass
    results["crashes"] = {"real_process_death_points": list(points), "restart_reconciled_before_admission": True}


def refused_port(value: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(0.1)
        return probe.connect_ex(("127.0.0.1", value)) != 0


def expiry(fixture: Fixture, results: dict, *, failure: str | None = None) -> None:
    kind = fixture.kind
    process = fixture.process(kind)
    process.terminate()
    process.wait(timeout=5)
    wait(lambda: refused_port(fixture.transport[kind]), 3, "old daemon still serving")
    fixture.launch(kind, lease=8)
    device = fixture.enroll(kind, 1, token=fixture.login()["access_token"] if kind == "alpha" else None)
    request, grant = fixture.issuance(kind, device)
    bundle = fixture.open_kit(kind, device, grant)
    with fixture.traffic(bundle) as connection:
        if failure:
            ready = json.loads((fixture.root / kind / "ready.json").read_bytes())
            os.kill(ready["worker"], signal.SIGKILL if failure == "killed" else signal.SIGSTOP)
        last_success = time.time()
        first_refusal = None
        while time.time() < grant["expires_at"] + 0.3:
            if fixture.echoed(connection):
                last_success = time.time()
            elif first_refusal is None:
                first_refusal = time.time()
            time.sleep(0.01)
        assert first_refusal is not None and first_refusal <= grant["expires_at"] and last_success < grant["expires_at"], "traffic survived transport expiry"
        assert not fixture.echoed(connection), "expired established traffic succeeded"
    if failure == "missed":
        os.kill(ready["worker"], signal.SIGCONT)
    if failure == "killed":
        fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device], status=410)
    with fixture.traffic(bundle, expected=False):
        pass
    if failure:
        process = fixture.process(kind)
        process.terminate()
        process.wait(timeout=5)
        fixture.launch(kind)
    fixture.request(kind, "/provider/v1/grants", request, token=fixture.credentials[device], status=410)
    with fixture.traffic(bundle, expected=False):
        pass
    results["expiry_" + (failure or "worker")] = {"expires_at": grant["expires_at"], "last_success": last_success,
        "first_refusal": first_refusal, "closed_before_deadline": True, "new_admission_refused": True, "restart_expired_identity_removed": bool(failure)}


def independent(fixture: Fixture, results: dict) -> None:
    tokens = fixture.login()
    account_until = time.time() + 60
    a = fixture.enroll("alpha", 1, token=tokens["access_token"])
    b = fixture.enroll("beta", 1)
    _, ga = fixture.issuance("alpha", a)
    _, gb = fixture.issuance("beta", b)
    alpha, beta = fixture.open_kit("alpha", a, ga), fixture.open_kit("beta", b, gb)
    # Manual setup uses only supported native commands, with no adapter or journal.
    manual = fixture.root / "manual/initial"
    command(fixture.arguments.setup, "init", "--host", "offline.example", "--port", str(fixture.transport["manual"]),
            "--output", str(manual), "--client-name", "offline")
    settings = json.loads((manual / "server/yumed.json").read_bytes())
    settings["endpoint"]["listen_addresses"] = ["127.0.0.1"]
    for adapter in settings["adapters"]:
        adapter["destinations"] = {"public": False, "networks": ["127.0.0.0/8"]}
    atomic(manual / "server/yumed.json", encoded(settings).encode())
    bundle = manual / "client"
    sealed = fixture.root / "manual/offline.kit"
    code = command(fixture.arguments.yume, "--seal-kit", str(bundle), "--output", str(sealed)).stdout
    opened = fixture.root / "manual/opened"
    command(fixture.arguments.yume, "--import-kit", str(sealed), "--into", str(opened), data=code)
    with (fixture.root / "manual/daemon.log").open("ab") as log:
        process = subprocess.Popen([str(fixture.arguments.yumed), "--config", str(manual / "server/yumed.json")],
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=log)
    fixture.processes.append(process)
    with fixture.traffic(alpha) as ca, fixture.traffic(beta) as cb, fixture.traffic(opened) as cm:
        # The account's real 60-second deadline passes without changing the transport lease.
        while time.time() <= account_until + 0.2:
            assert fixture.echoed(ca) and fixture.echoed(cb) and fixture.echoed(cm)
            time.sleep(0.1)
        fixture.request("alpha", "/provider/v1/catalog", fixture.base("alpha", a), token=tokens["access_token"], status=401)
        assert fixture.echoed(ca), "account-token expiry revoked valid transport"
        # Obtain fresh account authority, then remove all of this provider's issued identities.
        fresh = fixture.login()
        fixture.credentials[a] = fresh["access_token"]
        fixture.revoked("alpha", a, ga)
        wait(lambda: not fixture.echoed(ca), 2, "provider removal retained traffic")
        assert fixture.echoed(cb) and fixture.echoed(cm), "provider removal affected independent ownership"
        for kind in ("issuer", "alpha", "beta"):
            fixture.process(kind).terminate()
            fixture.process(kind).wait(timeout=5)
        assert fixture.echoed(cm), "offline manual traffic depended on provider"
    with fixture.traffic(alpha, expected=False):
        pass
    recovered = fixture.root / "manual/recovered-offline"
    command(fixture.arguments.yume, "--import-kit", str(sealed), "--into", str(recovered), data=code)
    with fixture.traffic(recovered):
        pass
    results["independent"] = {"real_account_token_expiry": True, "valid_transport_survived": True,
        "provider_removal_closed_traffic": True, "other_operator_and_manual_preserved": True, "manual_import_and_traffic_without_services": True}


CASES = {"bindings": bindings, "revocation": revocation, "refresh": refresh, "publication": publication, "crashes": crashes,
         "restart": restart_safety, "reload": reload_safety,
         "expiry": expiry, "killed": lambda f, r: expiry(f, r, failure="killed"),
         "missed": lambda f, r: expiry(f, r, failure="missed"), "independent": independent}


def native(arguments: argparse.Namespace) -> int:
    for binary in (arguments.yume, arguments.yumed, arguments.setup):
        assert binary.is_absolute() and binary.is_file() and os.access(binary, os.X_OK)
        assert binary.parent == arguments.yume.parent, "binaries have different build roots"
    assert arguments.source_root.is_absolute() and (arguments.source_root / "src/runtime/native_endpoint.cpp").is_file()
    private_directory(arguments.evidence)
    result = {"passed": False, "cases": {}, "binaries": {p.name: sha(p) for p in (arguments.yume, arguments.yumed, arguments.setup)},
              "python": sys.version, "executable": sys.executable, "attempt": arguments.case or "all", "role": arguments.role, "cleanup": []}
    start = time.monotonic()
    try:
        for name in ([arguments.case] if arguments.case else CASES):
            kinds = [arguments.operator] if arguments.operator else (["beta"] if name in ("bindings", "independent") else ["alpha", "beta"])
            for kind in kinds:
                with tempfile.TemporaryDirectory(prefix="p2-provider-") as temporary:
                    fixture = Fixture(Path(temporary), arguments)
                    fixture.kind = kind
                    observations = {}
                    try:
                        fixture.start()
                        CASES[name](fixture, observations)
                        result["cases"][name + "_" + kind] = observations
                    finally:
                        fixture.close()
                        logs = {str(p.relative_to(temporary)): p.read_text(errors="replace")[-16384:]
                                for p in Path(temporary).rglob("*.log")}
                        # These programs never log credentials. Keep diagnostic tails privately.
                        atomic(arguments.evidence / (name + "-" + kind + "-logs.json"), encoded(logs).encode())
                assert not Path(temporary).exists(), "owned ephemeral credentials survived cleanup"
                result["cleanup"].append({"case": name + "_" + kind, "temporary_root": temporary, "removed": True})
        result["passed"] = True
    except (AssertionError, OSError, RuntimeError, subprocess.SubprocessError, contract.Refusal) as error:
        result["error_type"] = type(error).__name__
        result["error"] = str(error) if isinstance(error, (AssertionError, RuntimeError)) else "fixture operation failed"
    finally:
        result["seconds"] = time.monotonic() - start
        atomic(arguments.evidence / "result.json", encoded(result).encode())
        print(encoded(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("yume", "yumed", "setup", "source-root", "evidence"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--case", choices=CASES)
    parser.add_argument("--operator", choices=("alpha", "beta"))
    parser.add_argument("--role", choices=("vpn", "relay"), default="vpn")
    arguments, remaining = parser.parse_known_args()
    if arguments.yume:
        if remaining or any(getattr(arguments, name) is None for name in ("yumed", "setup", "source_root", "evidence")):
            parser.error("native mode needs all executable, source and evidence paths and no unknown arguments")
        if sys.flags.optimize:
            parser.error("conformance assertions require Python without optimization")
        os.umask(0o077)
        raise SystemExit(native(arguments))
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)
