"""Durable operator-command adapter for the isolated provider fixture."""

from __future__ import annotations

import fcntl
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
import provider_conformance as contract
from provider_supervisor import Supervisor


def encoded(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def validate_traffic_address(value: str) -> str:
    """Accept one canonical unicast fixture destination, never a network or name."""
    if not isinstance(value, str):
        raise ValueError("traffic address must be a canonical unicast IPv4 address")
    address = ipaddress.IPv4Address(value)
    if (str(address) != value or address.is_loopback or address.is_unspecified or
            address.is_multicast or address.is_reserved or address.is_link_local or
            address in ipaddress.IPv4Network("0.0.0.0/8")):
        raise ValueError("traffic address must be a canonical unicast IPv4 address")
    return value


def private_directory(path: Path) -> None:
    details = path.lstat()
    if not stat.S_ISDIR(details.st_mode) or details.st_uid != os.getuid() or stat.S_IMODE(details.st_mode) != 0o700:
        raise ValueError("fixture directory must be private and owned")


def atomic(path: Path, data: bytes) -> None:
    """Replace one owned file durably, without claiming cross-file atomicity."""
    fd, name = tempfile.mkstemp(prefix=".provider-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def command(binary: Path, *arguments: str, data: bytes | None = None,
            environment: dict[str, str] | None = None, allowed: tuple[int, ...] = (0,)) -> subprocess.CompletedProcess:
    """Capture secret-bearing tool stdout privately and never include it in errors."""
    try:
        result = subprocess.run([str(binary), *arguments], input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=20, env=environment)
    except subprocess.TimeoutExpired:
        raise RuntimeError("operator command timed out") from None
    if result.returncode not in allowed:
        raise RuntimeError("operator command failed")
    return result


class Operator:
    """One process owns the journal and every mutation of this server store."""

    def __init__(self, root: Path, yume: Path, yumed: Path, setup: Path,
                 host: str, port: int, lease_seconds: int = 90,
                 traffic_address: str | None = None, traffic_dns_address: str | None = None,
                 role: str = "vpn") -> None:
        private_directory(root)
        contract.require(role in ("vpn", "relay"), "unsupported")
        contract.dns_host(host)
        contract.integer(port, 1024, 65535)
        contract.integer(lease_seconds, 5, 3600)
        self.root, self.yume, self.yumed, self.setup = root, yume, yumed, setup
        self.host, self.port, self.lease_seconds = host, port, lease_seconds
        self.role = role
        self.traffic_address = validate_traffic_address(traffic_address) if traffic_address is not None else None
        if traffic_dns_address is not None and self.traffic_address is None:
            raise ValueError("traffic DNS address requires a traffic address")
        self.traffic_dns_address = validate_traffic_address(traffic_dns_address) if traffic_dns_address is not None else None
        self.lock = (root / "writer.lock").open("a+")
        self.supervisor: Supervisor | None = None
        self.database: sqlite3.Connection | None = None
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.initialize()
        except BaseException:
            self.close()
            raise

    def initialize(self) -> None:
        root, setup, host, port = self.root, self.setup, self.host, self.port
        self.database = sqlite3.connect(root / "journal.sqlite")
        self.database.row_factory = sqlite3.Row
        self.database.execute("PRAGMA synchronous=FULL")
        self.database.executescript("""
            CREATE TABLE IF NOT EXISTS requests (
              principal TEXT NOT NULL, request_id TEXT NOT NULL, body TEXT NOT NULL,
              grant_id TEXT NOT NULL UNIQUE, deadline INTEGER NOT NULL, mono REAL NOT NULL,
              state TEXT NOT NULL, result TEXT, retain_until INTEGER NOT NULL,
              PRIMARY KEY (principal, request_id));
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS principals (
              device TEXT PRIMARY KEY, credential TEXT NOT NULL, expires INTEGER NOT NULL,
              owner TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (operation TEXT NOT NULL, grant_id TEXT NOT NULL, outcome INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS revocations (
              principal TEXT NOT NULL, request_id TEXT NOT NULL, body TEXT NOT NULL,
              result TEXT, retain_until INTEGER NOT NULL, PRIMARY KEY(principal,request_id));
        """)
        saved_role = self.database.execute("SELECT value FROM meta WHERE key='role'").fetchone()
        if saved_role is None:
            # An initialized store must never acquire a new application role.
            contract.require(not (root / "initial").exists() and
                             self.database.execute("SELECT count(*) FROM requests").fetchone()[0] == 0 and
                             self.database.execute("SELECT count(*) FROM principals").fetchone()[0] == 0, "binding")
            with self.database:
                self.database.execute("INSERT INTO meta VALUES ('role',?)", (self.role,))
        else:
            contract.require(saved_role["value"] == self.role, "binding")
        for name in ("bundles", "kits", "handoff"):
            (root / name).mkdir(mode=0o700, exist_ok=True)
        self.server = root / "initial/server"
        destinations = {"public": False, "networks": ["127.0.0.0/8"]}
        for address in (self.traffic_address, self.traffic_dns_address):
            if address is not None and address + "/32" not in destinations["networks"]:
                destinations["networks"].append(address + "/32")
        if not self.server.exists():
            command(setup, "init", "--host", host, "--port", str(port),
                    "--output", str(root / "initial"), "--client-name", "control")
            settings = json.loads((self.server / "yumed.json").read_bytes())
            settings["endpoint"]["listen_addresses"] = ["127.0.0.1"]
            settings["endpoint"]["event_loops"] = 1
            for adapter in settings["adapters"]:
                adapter["destinations"] = destinations
            atomic(self.server / "yumed.json", encoded(settings).encode())
        else:
            # A restart must use the originally selected destination authority.
            # Refuse a new option rather than mutating a running fixture's policy.
            settings = json.loads((self.server / "yumed.json").read_bytes())
            if not settings["adapters"] or any(adapter["destinations"] != destinations
                                               for adapter in settings["adapters"]):
                raise ValueError("fixture traffic destination policy changed")
        self.fault: str | None = None
        self.setup_environment: dict[str, str] | None = None
        self.supervisor = Supervisor(self.yumed, self.server / "yumed.json", root / "daemon.log")
        self.reconcile()
        self.arm()
        self.supervisor.call("start")

    def close(self) -> None:
        try:
            if self.supervisor is not None:
                self.supervisor.close()
        finally:
            if self.database is not None:
                self.database.close()
            self.lock.close()

    def checkpoint(self, point: str) -> None:
        if self.fault == point:
            os._exit(91)  # The real process dies, including its open supervision pipe.

    def entries(self) -> list[dict]:
        return json.loads((self.server / "credentials/authorized-keys.json").read_bytes())["keys"]

    def entry(self, name: str) -> dict | None:
        return next((entry for entry in self.entries() if entry["name"] == name), None)

    def row(self, grant_id: str) -> sqlite3.Row | None:
        return self.database.execute("SELECT * FROM requests WHERE grant_id=?", (grant_id,)).fetchone()

    def update(self, grant_id: str, state: str, result: dict | None = None) -> None:
        with self.database:
            self.database.execute("UPDATE requests SET state=?, result=COALESCE(?,result) WHERE grant_id=?",
                                  (state, encoded(result) if result is not None else None, grant_id))

    def arm(self) -> None:
        rows = self.database.execute("SELECT deadline,mono FROM requests WHERE state IN ('pending','active','removing')").fetchall()
        if rows:
            wall = min(row["deadline"] for row in rows)
            remaining = min(row["mono"] - time.monotonic() for row in rows)
            self.supervisor.call("arm", expires_at=wall, remaining=min(remaining, wall - time.time()))
        else:
            self.supervisor.call("arm", remaining=None)

    def remove(self, grant_id: str, *, reload: bool = True) -> None:
        row = self.row(grant_id)
        if row is None:
            raise RuntimeError("unknown journal grant")
        self.update(grant_id, "removing")
        if self.entry(grant_id) is not None:
            result = command(self.setup, "remove-client", "--server", str(self.server),
                             "--client-name", grant_id, allowed=(0, 1, 3))
            # A flush/deletion failure may follow publication. Verify actual authority.
            if self.entry(grant_id) is not None:
                raise RuntimeError("identity removal unconfirmed")
            if result.returncode:
                self.flush_store()
        # A crash after store replacement can precede the native file deletions.
        for suffix in ("-composite.pub.pem", "-access.psk"):
            (self.server / "credentials/authorized" / (grant_id + suffix)).unlink(missing_ok=True)
        if reload:
            self.supervisor.call("reload")
        self.checkpoint("after_remove")
        self.update(grant_id, "revoked")
        self.arm()

    def flush_store(self) -> None:
        for path in (self.server / "credentials/authorized-keys.json", self.server / "credentials"):
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        command(self.yumed, "--config", str(self.server / "yumed.json"), "--validate")

    def reconcile(self) -> None:
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        previous = dict(self.database.execute("SELECT key,value FROM meta"))
        untrusted = previous.get("boot", boot) != boot or float(previous.get("wall", "0")) > time.time() + 0.1
        for row in self.database.execute("SELECT * FROM revocations WHERE result IS NULL").fetchall():
            request = json.loads(row["body"])
            self.remove(request["grant_id"], reload=False)
            with self.database:
                self.database.execute("UPDATE revocations SET result=? WHERE principal=? AND request_id=?",
                                      (encoded({**request, "status": "revoked"}), row["principal"], row["request_id"]))
        for row in self.database.execute("SELECT * FROM requests").fetchall():
            grant_id = row["grant_id"]
            expired = untrusted or time.time() >= row["deadline"] - 1 or time.monotonic() >= row["mono"] - 1
            if row["state"] == "removing" or (row["state"] in ("pending", "active") and expired):
                self.remove(grant_id, reload=False)
            elif row["state"] == "pending":
                if self.entry(grant_id) is not None and (self.root / "bundles" / grant_id / "yume.json").is_file():
                    # Finish using the original identity, request and expiry, after daemon start.
                    continue
                self.remove(grant_id, reload=False)
                bundle = self.root / "bundles" / grant_id
                if bundle.exists():
                    shutil.rmtree(bundle)
                for directory, suffix in (("kits", ".kit"), ("handoff", ".code")):
                    (self.root / directory / (grant_id + suffix)).unlink(missing_ok=True)
                self.update(grant_id, "failed")
        with self.database:
            self.database.execute("INSERT OR REPLACE INTO meta VALUES ('boot',?)", (boot,))
            self.database.execute("INSERT OR REPLACE INTO meta VALUES ('wall',?)", (str(time.time()),))
        known = {row[0] for row in self.database.execute("SELECT grant_id FROM requests WHERE state IN ('active','pending')")}
        actual = {entry["name"] for entry in self.entries()}
        if "control" not in actual or not actual <= known | {"control"}:
            raise RuntimeError("unjournaled native authority prevents restart")
        self.flush_store()

    def sweep(self) -> None:
        for row in self.database.execute("SELECT * FROM requests WHERE state IN ('pending','active','removing')").fetchall():
            if row["state"] == "removing" or time.time() >= row["deadline"] - 1 or time.monotonic() >= row["mono"] - 1:
                try:
                    self.remove(row["grant_id"])
                except (RuntimeError, OSError):
                    self.supervisor.call("stop")
                    raise
        with self.database:
            self.database.execute("INSERT OR REPLACE INTO meta VALUES ('wall',?)", (str(time.time()),))

    def verify_bundle(self, grant_id: str) -> dict:
        bundle = self.root / "bundles" / grant_id
        settings = json.loads((bundle / "yume.json").read_bytes())
        contract.require(settings["role"] == "client", "unsupported")
        contract.require(settings["endpoint"]["host"] == self.host and settings["endpoint"]["port"] == self.port, "binding")
        contract.require("circuits" not in settings and "socks5_proxy" not in settings["endpoint"], "unsupported")
        for reference in settings["credentials"].values():
            contract.require(type(reference) is dict and reference.keys() == {"file"}, "unsupported")
            relative = Path(reference["file"])
            contract.require(not relative.is_absolute() and ".." not in relative.parts, "unsupported")
            path = bundle
            for part in relative.parts:
                path /= part
                contract.require(not path.is_symlink(), "unsupported")
            contract.require(stat.S_ISREG(path.lstat().st_mode), "unsupported")
        entry = self.entry(grant_id)
        contract.require(entry is not None, "unavailable")
        command(self.yume, "--config", str(bundle / "yume.json"), "--validate")
        self.flush_store()
        public = (bundle / "credentials/client-composite.pub.pem").read_bytes()
        contract.require(public == (self.server / "credentials" / entry["identity"]["file"]).read_bytes(), "binding")
        access = (bundle / settings["credentials"]["access_psk"]["file"]).read_bytes()
        contract.require(hmac.compare_digest(access, (self.server / "credentials" / entry["access_psk"]["file"]).read_bytes()), "binding")
        contract.require(not hmac.compare_digest(access, (bundle / settings["credentials"]["admission_key"]["file"]).read_bytes()), "binding")
        contract.require(entry["capabilities"] == [{"service": "tcp", "kind": "stream"}, {"service": "udp", "kind": "packet"}], "unsupported")
        server_public = (bundle / settings["credentials"]["server_identity"]["file"]).read_bytes()
        contract.require(server_public == (self.server / "credentials/server-composite.pub.pem").read_bytes(), "binding")
        return {"identity": entry["identity"]["sha256"], "server_key_sha256": hashlib.sha256(server_public).hexdigest()}

    def issue(self, principal: str, request: dict) -> dict:
        now = int(time.time())
        with self.database:
            self.database.execute("DELETE FROM requests WHERE retain_until<=? AND state IN ('revoked','failed')", (now,))
            self.database.execute("DELETE FROM revocations WHERE retain_until<=? AND result IS NOT NULL", (now,))
        contract.require(self.database.execute("SELECT 1 FROM revocations WHERE principal=? AND request_id=?",
                                              (principal, request["request_id"])).fetchone() is None, "conflict")
        existing = self.database.execute("SELECT * FROM requests WHERE principal=? AND request_id=?",
                                         (principal, request["request_id"])).fetchone()
        if existing is not None:
            contract.require(existing["body"] == encoded(request), "conflict")
            contract.require(now < existing["retain_until"], "stale")
            contract.require(existing["state"] not in ("revoked", "failed", "removing"), "revoked")
            contract.require(time.time() < existing["deadline"] - 1 and time.monotonic() < existing["mono"] - 1, "revoked")
            if existing["result"] is not None:
                return json.loads(existing["result"])
            return self.finish(existing)
        count = self.database.execute("SELECT count(*) FROM requests WHERE principal=?", (principal,)).fetchone()[0]
        count += self.database.execute("SELECT count(*) FROM revocations WHERE principal=?", (principal,)).fetchone()[0]
        contract.require(count < 64, "rate_limited")
        previous = request["previous_grant"]
        if previous is not None:
            row = self.row(previous)
            contract.require(row is not None and row["principal"] == principal and row["state"] == "active", "unauthorized")
            old = json.loads(row["body"])
            contract.require(all(request[k] == old[k] for k in ("provider", "device_id", "audience", "service_id", "server_id", "role")), "binding")
        grant_id = "g" + hashlib.sha256((principal + "\0" + request["request_id"]).encode()).hexdigest()[:40]
        deadline = now + self.lease_seconds
        with self.database:
            self.database.execute("INSERT INTO requests VALUES (?,?,?,?,?,?, 'pending',NULL,?)",
                                  (principal, request["request_id"], encoded(request), grant_id, deadline,
                                   time.monotonic() + deadline - time.time(), now + 86400))
        self.arm()
        self.checkpoint("after_journal")
        result = command(self.setup, "add-client", "--server", str(self.server), "--host", self.host,
                         "--output", str(self.root / "bundles" / grant_id), "--client-name", grant_id,
                         environment=self.setup_environment, allowed=(0, 3))
        with self.database:
            self.database.execute("INSERT INTO events VALUES ('add-client',?,?)", (grant_id, result.returncode))
        if result.returncode == 3:
            self.flush_store()
        self.checkpoint("after_add")
        return self.finish(self.row(grant_id))

    def finish(self, row: sqlite3.Row) -> dict:
        contract.require(time.time() < row["deadline"] - 1, "stale")
        grant_id = row["grant_id"]
        evidence = self.verify_bundle(grant_id)
        sealed = self.root / "kits" / (grant_id + ".kit")
        handoff = self.root / "handoff" / (grant_id + ".code")
        if not sealed.exists() or not handoff.exists():
            sealed.unlink(missing_ok=True)
            output = command(self.yume, "--seal-kit", str(self.root / "bundles" / grant_id), "--output", str(sealed))
            code = output.stdout.strip()
            if re.fullmatch(rb"[0-9A-HJKMNP-TV-Z]{5}(?:-[0-9A-HJKMNP-TV-Z]{5}){4}", code) is None:
                raise RuntimeError("invalid native opening code")
            atomic(handoff, code + b"\n")
        self.checkpoint("after_seal")
        request = json.loads(row["body"])
        generation = 1
        if request["previous_grant"] is not None:
            previous = self.row(request["previous_grant"])
            generation = json.loads(previous["result"])["generation"] + 1
            self.remove(request["previous_grant"])
        self.supervisor.call("reload")
        payload = sealed.read_bytes()
        issued = row["retain_until"] - 86400
        response = {**request, "grant_id": grant_id, "generation": generation,
                    "issued_at": issued, "refresh_after": issued, "expires_at": row["deadline"],
                    "client_identity": evidence["identity"], "kit_sha256": hashlib.sha256(payload).hexdigest(),
                    "kit_size": len(payload)}
        self.update(grant_id, "active", response)
        self.checkpoint("after_result")
        return response

    def revoke(self, principal: str, request: dict) -> dict:
        contract.require(self.database.execute("SELECT 1 FROM requests WHERE principal=? AND request_id=?",
                                              (principal, request["request_id"])).fetchone() is None, "conflict")
        row = self.database.execute("SELECT * FROM revocations WHERE principal=? AND request_id=?",
                                    (principal, request["request_id"])).fetchone()
        if row is not None:
            contract.require(row["body"] == encoded(request), "conflict")
            contract.require(time.time() < row["retain_until"], "stale")
            if row["result"] is not None:
                return json.loads(row["result"])
        else:
            grant = self.row(request["grant_id"])
            contract.require(grant is not None and grant["principal"] == principal, "unauthorized")
            count = sum(self.database.execute("SELECT count(*) FROM " + table + " WHERE principal=?", (principal,)).fetchone()[0]
                        for table in ("requests", "revocations"))
            contract.require(count < 64, "rate_limited")
            with self.database:
                self.database.execute("INSERT INTO revocations VALUES (?,?,?,NULL,?)",
                                      (principal, request["request_id"], encoded(request), int(time.time()) + 86400))
        if self.row(request["grant_id"])["state"] != "revoked":
            self.remove(request["grant_id"])
        response = {**request, "status": "revoked"}
        with self.database:
            self.database.execute("UPDATE revocations SET result=? WHERE principal=? AND request_id=?",
                                  (encoded(response), principal, request["request_id"]))
        return response
