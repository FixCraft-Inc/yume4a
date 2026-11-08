"""Offline reference assertions for docs/PROVIDERS.md, never an app client.

KitEvidence and commit outcomes are injected observations. They do not open
kits, implement a vault, verify OAuth, contact an issuer or control a daemon.
"""

from copy import deepcopy
from dataclasses import dataclass, field
import base64
import hashlib
import hmac
import json
import re
from typing import Any


CAPABILITIES = frozenset({"sealed-kit-1", "device-grants-1", "browser-pkce-s256", "anonymous-device-1",
                          "device-bound-enrollment-1", "server-location-1"})
ERRORS = frozenset({"malformed", "binding", "unsupported", "unauthorized",
                    "not_entitled", "conflict", "stale", "revoked",
                    "rate_limited", "unavailable"})
BASE_FIELDS = "v provider device_id audience"
REQUEST_FIELDS = BASE_FIELDS + " request_id service_id server_id role previous_grant"
GRANT_FIELDS = REQUEST_FIELDS + (
    " grant_id generation issued_at refresh_after expires_at client_identity kit_sha256 kit_size")


class Refusal(ValueError):
    """Stable error only, with no peer-controlled data in diagnostics."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def require(condition: bool, code: str = "malformed") -> None:
    if not condition:
        raise Refusal(code)


def object_fields(value: Any, fields: str) -> dict:
    require(type(value) is dict and value.keys() == set(fields.split()))
    return value


def integer(value: Any, low: int = 0, high: int = 9007199254740991) -> int:
    require(type(value) is int and low <= value <= high)
    return value


def string(value: Any, limit: int = 4096) -> str:
    require(type(value) is str)
    try:
        encoded = value.encode("utf-8")
    except UnicodeEncodeError:
        raise Refusal("malformed") from None
    require(0 < len(encoded) <= limit and all(ord(c) >= 32 and ord(c) != 127 for c in value))
    return value


def identifier(value: Any) -> str:
    require(type(value) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", value) is not None)
    return value


def digest(value: Any) -> str:
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None)
    return value


def device_credential(value: Any) -> str:
    require(type(value) is str and re.fullmatch(r"[A-Za-z0-9_-]{43}", value) is not None)
    decoded = base64.urlsafe_b64decode(value + "=")
    require(base64.urlsafe_b64encode(decoded).decode().rstrip("=") == value)
    return value


def sequence(value: Any, low: int = 0, high: int = 64) -> list:
    require(type(value) is list and low <= len(value) <= high)
    return value


def names(value: Any, low: int = 0, high: int = 16) -> list[str]:
    entries = sequence(value, low, high)
    for entry in entries:
        identifier(entry)
    require(len(set(entries)) == len(entries))
    return entries


def capabilities(value: Any, low: int = 0) -> None:
    require(set(names(value, low)) <= CAPABILITIES, "unsupported")


def dns_host(value: Any) -> str:
    string(value, 253)
    require(value == value.lower() and "." in value and not value.endswith("."))
    require(not re.fullmatch(r"[0-9.]+", value))
    for label in value.split("."):
        require(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) is not None)
    return value


def origin(value: Any) -> str:
    string(value, 512)
    match = re.fullmatch(r"https://([a-z0-9.-]+)(?::([1-9][0-9]{0,4}))?", value)
    require(match is not None, "binding")
    dns_host(match[1])
    if match[2] is not None:
        integer(int(match[2]), 1, 65535)
        require(int(match[2]) != 443, "binding")
    return value


def endpoint(value: Any, expected_origin: str | None = None) -> str:
    string(value, 512)
    # Reject escapes and parser-dependent spellings before any URL interpretation.
    match = re.fullmatch(r"(https://[a-z0-9.:-]+)(/[A-Za-z0-9/._~-]*)", value)
    require(match is not None, "binding")
    actual = origin(match[1])
    require(not any(part in (".", "..") for part in match[2].split("/")), "binding")
    require(expected_origin is None or actual == expected_origin, "binding")
    return value


def _pairs(pairs: list[tuple[str, Any]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def decode(raw: bytes, limit: int = 16384) -> Any:
    """Bound raw bytes and lexical depth before the recursive JSON parser."""
    require(type(raw) is bytes and 0 < len(raw) <= limit)
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise Refusal("malformed") from None
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            require(depth <= 8)
        elif char in "]}":
            depth -= 1
            require(depth >= 0)
    try:
        value = json.loads(text, object_pairs_hook=_pairs)
    except (ValueError, RecursionError):
        raise Refusal("malformed") from None
    _json_values(value)
    return value


def _json_values(value: Any) -> None:
    if type(value) is dict:
        for key, child in value.items():
            string(key)
            _json_values(child)
    elif type(value) is list:
        sequence(value)
        for child in value:
            _json_values(child)
    elif type(value) is str:
        string(value)
    elif type(value) is int:
        integer(value)
    else:
        require(value is None)  # No JSON booleans or floating-point numbers in v1.


@dataclass(frozen=True)
class Context:
    provider: str
    device_id: str
    role: str
    client_id: str
    redirect_uri: str
    accepted_issuer: str | None

    @property
    def audience(self) -> str:
        return self.provider + "/provider/v1"


def descriptor(value: Any, context: Context, now: int) -> dict:
    d = object_fields(value, "v provider label expires_at required roles auth")
    integer(d["v"], 1, 1)
    origin(d["provider"])
    require(d["provider"] == context.provider, "binding")
    string(d["label"], 80)
    integer(d["expires_at"])
    require(now < d["expires_at"] <= now + 3600, "stale")
    capabilities(d["required"], 2)
    require({"sealed-kit-1", "device-grants-1"} <= set(d["required"]), "unsupported")
    require(context.role in names(d["roles"], 1, 8) and context.role in {"vpn", "relay"}, "unsupported")
    auth = d["auth"]
    require(("browser-pkce-s256" in d["required"]) == (auth is not None), "unsupported")
    require(("device-bound-enrollment-1" in d["required"]) == (auth is not None), "unsupported")
    require(("anonymous-device-1" in d["required"]) == (auth is None), "unsupported")
    if auth is not None:
        object_fields(auth, "issuer authorization_endpoint token_endpoint revocation_endpoint client_id redirect_uri")
        origin(auth["issuer"])
        require(auth["issuer"] == context.accepted_issuer, "binding")
        for name in ("authorization_endpoint", "token_endpoint", "revocation_endpoint"):
            endpoint(auth[name], auth["issuer"])
        identifier(auth["client_id"])
        endpoint(auth["redirect_uri"])
        require(auth["client_id"] == context.client_id and
                auth["redirect_uri"] == context.redirect_uri, "binding")
    else:
        require(context.accepted_issuer is None, "binding")
    return deepcopy(d)


def bound(value: dict, context: Context) -> None:
    integer(value["v"], 1, 1)
    origin(value["provider"])
    identifier(value["device_id"])
    string(value["audience"], 512)
    require(value["provider"] == context.provider and
            value["device_id"] == context.device_id and
            value["audience"] == context.audience, "binding")


def catalog(value: Any, d: dict, context: Context, now: int) -> dict:
    c = object_fields(value, BASE_FIELDS + " issued_at expires_at services")
    bound(c, context)
    integer(c["issued_at"])
    integer(c["expires_at"])
    require(c["issued_at"] <= now < c["expires_at"] <= c["issued_at"] + 300 and
            c["expires_at"] <= d["expires_at"], "stale")
    service_ids = set()
    for service in sequence(c["services"], 0, 32):
        object_fields(service, "id label role required servers")
        identifier(service["id"])
        require(service["id"] not in service_ids)
        service_ids.add(service["id"])
        string(service["label"], 80)
        identifier(service["role"])
        require(service["role"] == context.role and service["role"] in d["roles"], "unsupported")
        capabilities(service["required"])
        server_ids = set()
        for server in sequence(service["servers"], 1, 32):
            object_fields(server, "id label host port server_key_sha256" +
                          (" country" if "server-location-1" in d["required"] else ""))
            if "server-location-1" in d["required"]:
                require(server["country"] is None or
                        (type(server["country"]) is str and re.fullmatch(r"[A-Z]{2}", server["country"]) is not None))
            identifier(server["id"])
            require(server["id"] not in server_ids)
            server_ids.add(server["id"])
            string(server["label"], 80)
            dns_host(server["host"])
            integer(server["port"], 1, 65535)
            digest(server["server_key_sha256"])
    return deepcopy(c)


def request(value: Any, context: Context, c: dict, now: int) -> dict:
    r = object_fields(value, REQUEST_FIELDS)
    bound(r, context)
    for name in ("request_id", "service_id", "server_id", "role"):
        identifier(r[name])
    if r["previous_grant"] is not None:
        identifier(r["previous_grant"])
    require(now < c["expires_at"], "stale")
    require(r["role"] == context.role, "unsupported")
    service = next((s for s in c["services"] if s["id"] == r["service_id"]), None)
    require(service is not None, "not_entitled")
    server = next((s for s in service["servers"] if s["id"] == r["server_id"]), None)
    require(server is not None, "not_entitled")
    return deepcopy(server)


def grant(value: Any, r: dict, previous: dict | None, now: int) -> dict:
    g = object_fields(value, GRANT_FIELDS)
    # All request bindings, including idempotency and previous generation, survive.
    require(all(type(g[key]) is type(r[key]) and g[key] == r[key] for key in r), "binding")
    identifier(g["grant_id"])
    integer(g["generation"], 1)
    for name in ("issued_at", "refresh_after", "expires_at"):
        integer(g[name])
    require(g["issued_at"] <= now < g["expires_at"] <= g["issued_at"] + 3600 and
            g["issued_at"] <= g["refresh_after"] < g["expires_at"], "stale")
    digest(g["client_identity"])
    digest(g["kit_sha256"])
    size = integer(g["kit_size"], 1068, 1049648)
    require((size - 44) % 1024 == 0)
    if previous is None:
        require(g["previous_grant"] is None and g["generation"] == 1, "state")
    else:
        require(now >= previous["refresh_after"], "state")
        require(g["previous_grant"] == previous["grant_id"] and
                g["generation"] == previous["generation"] + 1 and
                g["grant_id"] != previous["grant_id"] and
                g["client_identity"] != previous["client_identity"], "state")
        require(all(g[key] == previous[key] for key in
                    ("provider", "device_id", "audience", "service_id", "server_id", "role")), "binding")
    return deepcopy(g)


@dataclass(frozen=True)
class KitEvidence:
    """Synthetic results a later native/platform integration must establish."""

    sha256: str
    size: int
    host: str
    port: int
    server_key_sha256: str
    client_identity: str
    native_client_valid: bool = True
    contained_paths: bool = True
    app_policy_valid: bool = True


def kit_evidence(evidence: KitEvidence, g: dict, server: dict) -> None:
    require(evidence.native_client_valid and evidence.contained_paths and evidence.app_policy_valid,
            "unsupported")
    require(evidence.sha256 == g["kit_sha256"] and evidence.size == g["kit_size"] and
            evidence.client_identity == g["client_identity"] and
            evidence.host == server["host"] and evidence.port == server["port"] and
            evidence.server_key_sha256 == server["server_key_sha256"], "binding")


@dataclass(frozen=True)
class TokenPair:
    access: str = field(repr=False)
    refresh: str = field(repr=False)
    access_until: int
    refresh_until: int


def token_pair(value: Any, now: int, previous: TokenPair | None = None) -> TokenPair:
    t = object_fields(value, "token_type access_token expires_in refresh_token refresh_expires_in scope")
    require(t["token_type"] == "DPoP" and t["scope"] == "provider.enroll", "unsupported")
    for name in ("access_token", "refresh_token"):
        string(t[name])
        require(all(33 <= ord(c) <= 126 for c in t[name]))
    integer(t["expires_in"], 60, 900)
    integer(t["refresh_expires_in"], t["expires_in"] + 1, 2592000)
    require(t["access_token"] != t["refresh_token"], "binding")
    if previous is not None:
        require(now < previous.refresh_until and
                t["refresh_token"] != previous.refresh and
                t["access_token"] != previous.access, "revoked")
    return TokenPair(t["access_token"], t["refresh_token"],
                     now + t["expires_in"], now + t["refresh_expires_in"])


def opening_code(value: Any, context: Context, grant_id: str) -> str:
    """Validate delivered metadata before its code can reach the native opener."""
    response = object_fields(value, BASE_FIELDS + " grant_id opening_code")
    bound(response, context)
    identifier(response["grant_id"])
    require(response["grant_id"] == grant_id, "binding")
    code = response["opening_code"]
    require(type(code) is str and re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{25}", code) is not None)
    return code


@dataclass(frozen=True)
class Attempt:
    provider: str
    device_id: str
    issuer: str
    redirect_uri: str
    state: str
    started_at: int


def callback(value: Any, actual_redirect: str, pending: Attempt, context: Context, now: int) -> None:
    object_fields(value, "iss state code")
    string(value["code"], 512)
    require(re.fullmatch(r"[A-Za-z0-9_-]{43}", pending.state) is not None, "state")
    require(pending.started_at <= now < pending.started_at + 300, "stale")
    require(value["iss"] == pending.issuer == context.accepted_issuer and
            value["state"] == pending.state and actual_redirect == pending.redirect_uri == context.redirect_uri and
            pending.provider == context.provider and pending.device_id == context.device_id, "binding")


def error_response(value: Any) -> None:
    object_fields(value, "v error retry_after")
    integer(value["v"], 1, 1)
    require(type(value["error"]) is str and value["error"] in ERRORS)
    integer(value["retry_after"], 0, 300)
    require(value["retry_after"] == 0 or value["error"] in {"rate_limited", "unavailable"})


def revoke_response(value: Any, r: dict, context: Context) -> None:
    object_fields(r, BASE_FIELDS + " request_id grant_id")
    bound(r, context)
    identifier(r["request_id"])
    identifier(r["grant_id"])
    object_fields(value, BASE_FIELDS + " request_id grant_id status")
    require(all(type(value[key]) is type(r[key]) and value[key] == r[key] for key in r) and
            value["status"] == "revoked", "binding")


@dataclass
class Enrollment:
    """Single-provider state oracle. No I/O, secrets or persistent installation."""

    context: Context
    description: dict
    state: str = "Added"
    epoch: int = 0
    pending: Attempt | None = None
    tokens: TokenPair | None = field(default=None, repr=False)
    anonymous_credential: str | None = field(default=None, repr=False)
    anonymous_until: int = 0
    installed: dict | None = None
    staged: dict | None = None
    connected: bool = False
    cleanup_pending: bool = False
    _prior_state: str | None = None
    _exchange_pending: bool = False
    _exchange_until: int = 0
    _transport_blocked: bool = False

    def __post_init__(self) -> None:
        if self.description["auth"] is None:
            self.state = "Authorized"

    def begin_authorization(self, state: str, now: int) -> None:
        require(self.state in {"Added", "Authorized", "Ready", "Suspended"} and self.pending is None,
                "state")
        require(self.description["auth"] is not None, "state")
        require(now < self.description["expires_at"], "stale")
        require(re.fullmatch(r"[A-Za-z0-9_-]{43}", state) is not None)
        self.pending = Attempt(self.context.provider, self.context.device_id,
                               self.description["auth"]["issuer"], self.context.redirect_uri, state, now)
        self._exchange_pending = False
        self.tokens = None
        self.state = "Authorizing"

    def accept_callback(self, value: dict, actual_redirect: str, now: int) -> None:
        require(self.state == "Authorizing" and self.pending is not None, "state")
        pending, self.pending = self.pending, None
        # A callback consumes the attempt, including rejected exchanges.
        self.state = "Added"
        callback(value, actual_redirect, pending, self.context, now)
        self._exchange_pending = True
        self._exchange_until = min(pending.started_at + 300, now + 30)
        self.state = "Authorizing"

    def store_tokens(self, value: dict, now: int, *, committed: bool = True) -> None:
        require(self.state in {"Authorizing", "Authorized", "Ready", "Suspended"} and
                self.description["auth"] is not None and
                (self._exchange_pending or (self.tokens is not None and self.pending is None)), "state")
        if self._exchange_pending:
            if now >= self._exchange_until:
                self._exchange_pending = False
                self.state = "Added"
                raise Refusal("stale")
        self._exchange_pending = False
        try:
            replacement = token_pair(value, now, self.tokens)
        except Refusal:
            self.tokens = None
            self.state = "Suspended"
            raise
        if not committed:
            self.tokens = None
            self.state = "Suspended"
            raise Refusal("storage")
        self.tokens = replacement
        self.state = ("Suspended" if self._transport_blocked else
                      "Ready" if self.installed is not None else "Authorized")

    def api_allowed(self, now: int) -> None:
        require(self.state in {"Authorized", "Ready", "Suspended"} and self.pending is None, "state")
        require(now < self.description["expires_at"], "stale")
        if self.description["auth"] is not None:
            require(self.tokens is not None and now < self.tokens.access_until, "unauthorized")
        else:
            require(self.anonymous_credential is not None and now < self.anonymous_until, "unauthorized")
            device_credential(self.anonymous_credential)

    def stage(self, g: dict, r: dict, c: dict, evidence: KitEvidence, now: int) -> int:
        self.api_allowed(now)
        current_catalog = catalog(c, self.description, self.context, now)
        server = request(r, self.context, current_catalog, now)
        replacement = grant(g, r, self.installed, now)
        kit_evidence(evidence, replacement, server)
        self._prior_state = self.state
        self.staged = replacement
        self.connected = False
        self.state = "Installing"
        return self.epoch

    def commit(self, epoch: int, now: int, *, outcome: str = "committed") -> None:
        require(self.state == "Installing" and epoch == self.epoch and self.staged is not None, "state")
        require(now < self.staged["expires_at"], "stale")
        require(outcome in {"committed", "failed", "crash_before", "crash_after"})
        if outcome in {"failed", "crash_before"}:
            self.staged = None
            # Remote refresh might already have revoked the retained record.
            self.state = "Suspended" if self.installed is not None else self._prior_state
            self._transport_blocked = self.installed is not None
            raise Refusal("storage")
        self.installed, self.staged = self.staged, None
        self._transport_blocked = False
        self.state = "Ready"

    def cancel(self) -> None:
        require(self.state != "Removed", "state")
        self.epoch += 1
        self.pending = None
        self._exchange_pending = False
        self.staged = None
        self.connected = False
        self.state = "Suspended" if self.installed else "Added"
        self._transport_blocked = self.installed is not None
        if self.description["auth"] is None and self.installed is None:
            self.state = "Authorized"

    def expire_or_revoke(self, now: int, *, revoked: bool = False, trusted_clock: bool = True) -> None:
        if self.state == "Removed":
            return
        if revoked or not trusted_clock or (self.installed and now >= self.installed["expires_at"]):
            self.connected = False
            self.epoch += 1
            self.staged = None
            self.state = "Suspended"
            self._transport_blocked = True

    def connect(self, now: int) -> None:
        self.expire_or_revoke(now)
        require(self.state == "Ready" and self.installed is not None and not self._transport_blocked, "state")
        self.connected = True

    def remove(self, *, remote_confirmed: bool = False, vault_locked: bool = False) -> str:
        self.epoch += 1
        self.state = "Removed"
        self.connected = False
        self.pending = None
        self._exchange_pending = False
        self.staged = None
        self.tokens = None
        self.anonymous_credential = None
        self.installed = None
        self.cleanup_pending = vault_locked
        return "confirmed" if remote_confirmed else "unknown"


@dataclass
class Idempotency:
    """Provider-side synthetic journal, scoped to one authenticated principal."""

    entries: dict[str, tuple[dict, dict, int]] = field(default_factory=dict)

    def publish(self, r: dict, g: dict, now: int) -> dict:
        key = r["request_id"]
        previous = self.entries.get(key)
        if previous is not None:
            require(previous[0] == r, "conflict")
            require(now < previous[2], "stale")
            return deepcopy(previous[1])
        require(len(self.entries) < 64, "rate_limited")
        self.entries[key] = (deepcopy(r), deepcopy(g), now + 86400)
        return deepcopy(g)


@dataclass
class IdentityLedger:
    """Check provider-issued identity isolation, including idempotent repeats."""

    owners: dict[str, tuple[str, str, str]] = field(default_factory=dict)

    def claim(self, g: dict) -> None:
        key = digest(g["client_identity"])
        owner = (g["provider"], g["device_id"], g["grant_id"])
        require(key not in self.owners or self.owners[key] == owner, "binding")
        self.owners[key] = owner


def manual_kit(evidence: KitEvidence) -> str:
    require(evidence.native_client_valid and evidence.contained_paths and evidence.app_policy_valid,
            "unsupported")
    return digest(evidence.sha256)


@dataclass
class AnonymousDevices:
    """Synthetic server ownership oracle. No network or provisioning operations."""

    owners: dict[tuple[str, str], tuple[bytes, int]] = field(default_factory=dict, repr=False)
    max_devices: int = 32

    def authorize(self, context: Context, target: str, credential: str, now: int,
                  *, first_catalog: bool = False) -> None:
        require(origin(target) == context.provider, "binding")
        fingerprint = hashlib.sha256(device_credential(credential).encode("ascii")).digest()
        key = (context.provider, context.device_id)
        if key not in self.owners:
            require(first_catalog, "unauthorized")
            integer(self.max_devices, 1, 4096)
            require(len(self.owners) < self.max_devices, "rate_limited")
            self.owners[key] = (fingerprint, now + 2592000)
        stored, until = self.owners[key]
        require(now < until and hmac.compare_digest(stored, fingerprint), "unauthorized")


def http_boundary(status: int, content_type: str, encoding: str | None,
                  elapsed_ms: int, actual_origin: str, expected_origin: str) -> None:
    """Assert injected HTTP observations, without implementing a transport."""
    require(origin(actual_origin) == expected_origin and not 300 <= status < 400, "binding")
    require(content_type == "application/json" and encoding is None)
    require(0 <= elapsed_ms < 30000, "unavailable")
