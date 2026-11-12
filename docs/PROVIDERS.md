# Provider contract 1

This is the public application protocol for optional YUME providers. The Android
application supports manual kits and optional provider enrollment through the
[consumer interface](CONSUMER_SERVICES.md). The executable
[conformance model](../tools/provider_conformance.py)
and [cases](../tools/test_provider_conformance.py) exercise synthetic data without
a network, issuer, Android runtime or production provider implementation.
The [Android client package](PROVIDER_CLIENT.md) documents its implementation
boundaries and platform checks separately from these protocol requirements.

The provider controls access to its resources. The application owns provider
selection, local storage and routing consent. YUME owns transport authentication
and the strict native configuration parser. An account, device ID or enabled UI
setting never grants transport access by itself. Contract version `v: 1` is
independent of application, ABI, configuration and wire versions.

## Selection and trust

Adding a provider requires an explicit user choice of its HTTPS origin. There
is no default provider, global directory lookup or executable provider plugin.
Before authorization, show the origin, label, issuer (if present) and requested
service role. Labels and reachability hints are untrusted display data. They
must not choose credentials, replace keys or change routing policy.

An origin is `https://` plus a lowercase ASCII DNS host (IDNs use A-labels),
optionally a non-default port from 1 to 65535. No userinfo, IP literal, trailing
dot, path, query, fragment, escape, whitespace or explicit `:443` is allowed.
Each DNS label has 1 to 63 letters, digits or internal hyphens, the complete
host at most 253 bytes and at least two labels. This narrow spelling makes equality exact.
Self-hosted use can use a DNS name and platform-trusted private CA. HTTP bootstrap
and certificate bypasses are forbidden. Manual import works without DNS or TLS.

Fetch `/.well-known/yume-provider` only on the selected origin. API paths below
are fixed under that origin's `/provider/v1`. Refuse HTTP redirects on descriptor,
API, token and kit requests, including same-origin redirects. Disable cookies on
API requests. Never forward an account token to a transport host or another
origin. Replacing an origin or issuer requires fresh consent and authorization.
Do not migrate old tokens to the replacement.

## Encoding and bounds

All JSON objects below are closed. Fields in each table are required, except
where an alternative is stated. Unknown keys, duplicate keys, invalid UTF-8,
BOMs, unpaired surrogates, control characters in strings, non-finite numbers,
fractional numbers and integer booleans are errors. Integers are in
`0..9007199254740991`. Nesting is at most 8 containers. Bound bytes before
parsing and depth before recursive decoding. No JSON string exceeds 4096 UTF-8
bytes. Arrays contain distinct entries and at most 64 elements unless a smaller
bound follows. Limits include decoded and decompressed bytes. Reject compression
on these responses instead of accepting unbounded expansion.

| Value | Bound |
| --- | --- |
| Descriptor, request, OAuth response or error | 16384 bytes each |
| Catalog or grant metadata | 65536 bytes each |
| Identifier | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` |
| Label | 1 to 80 UTF-8 bytes, display as text |
| Capability name | Same alphabet as identifier, at most 64 bytes |
| URL | At most 512 ASCII bytes, HTTPS, canonical origin above |
| URL path | `/` followed by ASCII letters, digits, `/._~-`, no `.` or `..` component |
| Opaque account token | 1 to 4096 visible ASCII bytes, never interpreted as JSON |
| Timestamp | Integer UTC Unix seconds, checked against trusted current time |
| SHA-256 | Exactly 64 lowercase hexadecimal characters |

Non-browser requests have a 10 second connect deadline and a 30 second total
deadline, including body reading. One authorization attempt lasts at most 300
seconds. One attempt and one mutating operation per provider/device may run at
a time. An app stores at most 8 providers and 32 installed grants in total.
Cancellation invalidates the operation generation before releasing resources.
Late callbacks or completions cannot publish state. After clock rollback,
suspend grants until time can be established. A client with no trusted time
may start on a held grant that its last trusted time does not show expired,
since the node enforces expiry. The Android client's
[trusted time](PROVIDER_CLIENT.md#trusted-time) states its rule. While running,
a monotonic deadline must also prevent wall-clock rollback extending a lease.
There is no expiry grace period.

## Descriptor and capabilities

| Field | Type and meaning |
| --- | --- |
| `v` | Integer `1` |
| `provider` | Exactly the selected origin, also the provider's stable local key |
| `label` | Label |
| `expires_at` | `now < expires_at <= now + 3600` |
| `required` | 2 to 16 capability names, including `sealed-kit-1` and `device-grants-1` |
| `roles` | 1 to 8 role identifiers |
| `auth` | `null`, or the authorization object below |

`sealed-kit-1` means delivery through the existing native sealed-kit opener.
`device-grants-1` means the complete bounded transport lifecycle here, including
server-enforced expiry and revocation. `browser-pkce-s256` and
`device-bound-enrollment-1` are both required exactly when `auth` is present.
The second means the device-bound tokens and opening-code delivery described
under [Device-bound enrollment](#device-bound-enrollment). `anonymous-device-1`
is required exactly when `auth` is null and means the device credential rules
below. `server-location-1` is optional for the operator. When required, every
catalog server carries the `country` member defined there. An unknown required capability
or a selected role unsupported by the consuming app fails visibly with
`unsupported`. Other advertised roles may be left unselected. Do not silently omit a requirement or
substitute another role. `vpn` names a VPN transport, and `relay` names an
application's named relay transport. `relay` alone defines no calling, signaling,
directory or end-to-end identity protocol. yume4a's initial role is `vpn`.

Cache the descriptor only until `expires_at`, for display and exact binding.
Revalidate it before authorization or provisioning. A changed issuer or public
client registration suspends enrollment and requires consent again. A provider
that cannot be reached leaves the enrollment and its lease unchanged. Neither a
fresh descriptor nor a cached server list proves entitlement.

### Optional browser authorization

The authorization object has exactly `issuer`, `authorization_endpoint`,
`token_endpoint`, `revocation_endpoint`, `client_id` and `redirect_uri`.
The issuer is a canonical HTTPS origin that the user accepts separately from
the provider origin. All three endpoints must have that issuer's origin and
a bounded path. Version 1 uses these pinned endpoints directly, with normal
TLS verification. It requires no issuer discovery or additional network lookup.

The app is a public OAuth client. Each operator registers the app's published
application ID, public client ID and fixed verified HTTPS app-link callback
with its issuer. The app ships that callback in its Android intent filter and
checks OS app-link verification. A registration record, selected by application
ID, must match the descriptor's client ID and callback exactly. A descriptor
cannot invent a callback or select a different application's registration.
Operators publish registration instructions, requiring no client secret or
private SDK. No custom-scheme, loopback, wildcard or provider-chosen redirect is
supported in contract 1. The synthetic callback domain in the fixtures is not
a registration for a released application.

Use Authorization Code with PKCE S256 in the external browser through an
established native OAuth library. Generate an unpredictable 32-byte state and
a fresh 32-byte PKCE verifier per attempt, encode them as unpadded base64url,
and send the verifier only to the pinned token endpoint. Send `response_type=code`,
the registered client ID and redirect, `scope=provider.enroll`, `state`,
`code_challenge`, `code_challenge_method=S256` and the RFC 8707
`resource=<provider>/provider/v1` parameter. Bind the pending
attempt to provider, issuer, device, client ID, exact redirect, state and its
deadline. Request only `provider.enroll`. Require the authorization response's
`iss` (RFC 9207) to equal the issuer. Accept one bounded code response only,
consume the pending attempt before exchange, and reject unsolicited, duplicate,
expired, cancelled or mismatched callbacks. No token or kit code goes in an
app link. OIDC/ID tokens are outside version 1 and must not be used as account
or contact-identity proof. A required OIDC capability therefore fails visibly.

The successful standard token response has exactly `token_type: "DPoP"`,
`access_token`, `expires_in` (60 to 900 seconds), `refresh_token`,
`refresh_expires_in` (greater than `expires_in`, at most 2592000 seconds), and
`scope: "provider.enroll"`. Request the same resource on code exchange and
refresh. Token audience is the selected provider API,
`<provider>/provider/v1`. The issuer and API enforce it. The client records
the binding with the opaque tokens and never infers authority by decoding them.
Refresh is serialized, rotates both tokens and durably replaces the pair
before another refresh. A reused refresh token invalidates that account token
family on the server. Ambiguous exchange/refresh loss requires sign-in again,
without replaying a single-use code or refresh token. Account token expiry
blocks API calls but does not by itself revoke a transport grant.

### Device-bound enrollment

Signing in and choosing a server completes enrollment. There is no opening
code to copy and no file to import. The account token family is bound to a
key that never leaves the device, and the provider releases a grant's opening
code only to that key.

The application creates a fresh non-exportable P-256 signing key for each
sign-in (on Android, an Android Keystore key). Every code exchange and refresh
carries an RFC 9449 DPoP proof. The issuer binds the new token family to the
key's RFC 7638 thumbprint at code exchange. A refresh must prove the same key.
A refresh presented with another key ends the whole family, as replay does.
Every provider API request sends `Authorization: DPoP <access token>` and a
fresh proof for that exact request. A bare Bearer token, a missing, repeated or
replayed proof, or a proof from another key is `unauthorized`.

A proof is a compact JWS. Its header has exactly `typ: "dpop+jwt"`,
`alg: "ES256"` and `jwk`, where `jwk` has exactly `kty: "EC"`, `crv: "P-256"`,
`x` and `y` (each 43 unpadded base64url characters). Its claims are exactly
`jti` (32 random bytes, unpadded base64url), `htm` (the request method), `htu`
(the exact request URL, which has no query or fragment) and `iat` (trusted
Unix seconds). API requests add `ath`, the unpadded base64url SHA-256 of the
access token. The signature is the 64-byte R || S form. The server accepts
`iat` within 60 seconds of its clock and each key/`jti` pair once. A proof is
at most 2048 bytes. Contract 1 uses no server nonce.

After a successful grant and kit download, the device posts exactly `v`,
`provider`, `device_id`, `audience` and `grant_id` to
`POST /provider/v1/opening-code`. The response repeats those fields and adds
`opening_code`, the 25-character uppercase Crockford base32 code with no
separators. The server releases it only to the token family that requested
the grant, for the same per-provider device, while the grant is active and
entitled. The code never appears in the descriptor, catalog, callback, a grant
response or logs. The application validates the kit with it at once, keeps
only the opened result in its vault and clears its copy of the code.

What this protects: a copied access or refresh token, whether from a log, a
backup, a proxy or a misdirected request, cannot fetch a catalog, create a
grant, download a kit, read a code or refresh, because each needs a signature
from the device key. The authorization code is still bound by PKCE and the
app's callback link, which must reach this app alone
([browser authentication](PROVIDER_CLIENT.md#browser-authentication)). What it
does not protect: an attacker who controls the device while the application is
unlocked can use the key, as they could use
the opened credentials. The remedy is account-side revocation of that family
and its grants. An operator browser page that shows codes to the signed-in
account, if one exists, is outside this contract.

Losing the key (cleared application data, a Keystore reset) ends the local
family. The application signs in again with a new key. Old grants still expire
on the server, and revocation of the old family closes them. Removing a
provider deletes its key after the bounded revocation attempt. Keys whose
enrollment no longer exists are deleted at the next start.

With `auth: null`, no account token is obtained or sent. Provisioning is public,
subject to quotas and the per-device credential below. An operator needing
account authorization must advertise browser authorization.

## API messages

Send JSON as `application/json`. Responses use the same type, `Cache-Control:
no-store` and no cookies. Authenticate API calls with the DPoP-bound access
token and a fresh proof when `auth` is present. Server authorization is required independently of what
the client validates. Device IDs are fresh random 128-bit, per-provider
installation identifiers encoded as unpadded base64url, never a hardware ID or
proof of device possession. The readable IDs in the fixtures are synthetic.

For `auth: null`, generate and durably store a separate random 256-bit device
API credential before the first request, encoded as 43 unpadded base64url
characters. Its lifetime is at most 2592000 seconds from local creation and
from first server registration, whichever ends earlier. Send it as the HTTPS
API's Bearer token. The first catalog request
atomically binds the device ID to a hash of this credential on that provider.
Later calls, kit downloads, refresh and revocation must verify that binding
with a constant-time comparison. A collision with a different credential is
`unauthorized`, without changing the existing principal. This credential manages
only that anonymous device, grants no account entitlement and is never a
transport PSK or kit-opening code. Scope it to the exact provider API origin,
store it in the vault, exclude it from backups and erase it on removal. Do not
send it to another operator or an issuer. Losing it requires fresh device
enrollment, while the old transport lease still expires on the server. There is
no device-credential refresh in version 1. Expiry requires a new device ID and
credential. Retain a server-side credential hash only through its last grant's
expiry and the 86400 second idempotency window, then remove it.
Operators bound anonymous principals and work by quota, returning `rate_limited`
before allocation. A device ID alone never authorizes access to an issued grant.

### Entitled catalog

`POST /provider/v1/catalog` takes exactly `v`, `provider`, `device_id` and
`audience`. `v` is 1, the provider is the selected origin, `device_id` an
identifier and `audience` is `<provider>/provider/v1`.

Its response repeats these four fields and adds `issued_at`, `expires_at` and
`services`. Require `issued_at <= now < expires_at <= issued_at + 300` and
`expires_at <= descriptor.expires_at`. `services` contains 0 to 32 entries.
Each service has exactly `id`, `label`, `role`, `required`, `servers`. Its role
must be requested by the app and advertised by the descriptor. `required` has
0 to 16 supported capability names. `servers` has 1 to 32 entries, each with
exactly `id`, `label`, `host`, `port`, `server_key_sha256`, plus `country` when
the descriptor requires `server-location-1`. `country` is `null` or an assigned
ISO 3166-1 alpha-2 code in uppercase. It is the operator's declared hosting
country, for display. It is not proof of where traffic leaves, and neither is
the endpoint address or its DNS resolution. Unknown stays `null`. Server IDs are unique
within a service, and service IDs within the catalog. Host uses the descriptor's
DNS spelling rules, port is 1 to 65535. `server_key_sha256` hashes the exact
bytes of the kit's `credentials.server_identity` public file. This binds the
chosen catalog entry to the trust material the native handshake will use.

The authenticated API returns only entitled services. A public-provisioning
catalog lists publicly requestable services. Either may still refuse a grant.
Once stale, catalog entries are display-only until fetched again. An empty
catalog is a valid no-entitlement result. No token travels to a listed host.

### Per-device grant and refresh

`POST /provider/v1/grants` takes exactly `v`, `provider`, `device_id`,
`audience`, `request_id`, `service_id`, `server_id`, `role`, `previous_grant`.
The first four fields have the catalog meanings. The next four are identifiers.
`previous_grant` is `null` for initial issuance or the installed grant ID for
refresh. Bind selection to a fresh catalog. A refresh keeps the same provider,
device, service, server and role. Moving servers requires a new explicit choice.
A client may instead renew with a fresh initial grant (`previous_grant` null)
while its current grant still works, then revoke the current one. The shared
Android client does this, at the later of `refresh_after` and half the lease.

`request_id` is a fresh random identifier and an idempotency key, bound to the
authenticated account (or public device), exact body and operation. Keep its
result for 86400 seconds. A principal may start at most 64 operations in any
hour, since grants renew at half their lease and each replaced grant is revoked. Refuse
new requests with `rate_limited` when full, preserving live idempotency records.
An identical retry returns the same grant, with its
original expiry. A changed body with the same key is `conflict`. After the
30 second deadline, repeat the identical request only by explicit action, as
often as the person retries, until a definitive answer (`revoked`,
`not_entitled`, `binding`, `malformed` or `unsupported`) ends it. Never
automatically generate another grant to recover an ambiguous response.

The successful response repeats every request field and adds:

| Field | Type and meaning |
| --- | --- |
| `grant_id` | New identifier, different from `previous_grant` |
| `generation` | 1 initially, then previous generation plus 1 |
| `issued_at` | `<= now` |
| `refresh_after` | Earliest refresh time, `issued_at <= refresh_after < expires_at` |
| `expires_at` | `now < expires_at <= issued_at + 3600` |
| `client_identity` | New per-grant native composite identity fingerprint, SHA-256 |
| `kit_sha256` | Hash of the complete sealed file |
| `kit_size` | 1068 to 1049648 bytes, `(kit_size - 44) % 1024 == 0`, from sealed kit 1 |

The kit is `GET /provider/v1/grants/<grant_id>/kit` on the same origin with the
same authorization and device/account ownership checks. Send exactly one
`X-Device-ID` header containing the grant request's `device_id`. The server
checks it against the authenticated owner. Never accept an
arbitrary download URL. With browser authorization, the bound device fetches
the 25-character opening code as described under
[Device-bound enrollment](#device-bound-enrollment). Public provisioning has no
device-bound account, so its code still arrives through a separate
authenticated operator channel, such as an offline handoff. A separate HTTP
request with the same anonymous credential is not a separate trust channel.
The code must not appear in the descriptor, catalog, callback or logs. Both
file and code together are transport credentials. Sealing does not
authenticate an operator by itself.

Initial provisioning creates a fresh client identity and access PSK per device.
Refresh rotates them, confirms removal of the previous identity and closes its
active sessions before returning success. A lost refresh response can therefore
interrupt the old session. Identical idempotent retry recovers the new grant.
Reject reused identities across devices or grant generations. The server binds
the grant to the entitled service and selected server. Account tokens never
become transport PSKs, admission keys, local communication keys or media keys.

### Revocation and errors

`POST /provider/v1/revoke` takes `v`, `provider`, `device_id`, `audience`,
`request_id`, `grant_id`. Revoke only the caller's grant. A successful response
repeats those fields and adds `status: "revoked"`. Repeated requests for that
owned grant succeed. Unknown grants belonging to another device/account reveal
no ownership information. Success means the identity is removed and reload
completed, including closure of established sessions. A timeout leaves remote
status unknown, never confirmed revoked. Logout additionally revokes the
account refresh-token family through the issuer's revocation endpoint.

Errors have exactly `v: 1`, `error`, `retry_after`. `retry_after` is an integer
0 to 300, nonzero only for `rate_limited` or `unavailable`. Use stable codes
`malformed` (HTTP 400), `binding` (400), `unsupported` (422), `unauthorized`
(401), `not_entitled` (403), `conflict` (409), `stale` (409), `revoked` (410),
`rate_limited` (429), `unavailable` (503). No peer text or credential is echoed.
Local failures add `state`, `storage`, `cancelled`, `link_unverified`, when
Android has not verified the client's own callback link, and
`clock_unavailable`, when the client has no trusted time yet. Refusal
never changes provider, TLS verification, route length or direct-routing
consent. A single visible error ends the attempt, with no unbounded retry or
login loop.

## Transport enforcement and existing tooling

Every grant is a lease. The operator must refuse new authentication and close
all traffic using its identity by `expires_at`, even if the client is modified,
asleep, offline from the API or never refreshes. Account expiry is independent.
Operators must durably record pending provisioning, identity mappings, expiry
and idempotency results before returning a grant. Serialize each server's store
writer, reconcile partial publication and verify the actual reload outcome.
Do not count sending a signal or a successful HTTP response as enforcement.

Current `yume-setup init` / `add-client`, `yume --seal-kit`, `remove-client`
and daemon credential reload are the reuse path. `add-client` grants the
standard `tcp` and `udp` services and can report partial publication (exit 3)
after store replacement. It provides no multi-file transaction or grant expiry
worker. Reload refuses invalid stores and preserves the old authority. Removing
an identity closes its sessions. `remove-client` refuses the last identity.
An isolated fixture can keep an unrelated non-issued control identity so that
every issued device can be removed. Never issue that control identity to users.
Narrower service policy needs explicit operator-side store validation. A label
must not claim narrower grants than the loaded daemon policy.

An expiry scheduler needs an independent fail-closed supervisor: stop serving
before the earliest outstanding deadline if enforcement cannot be confirmed.
After a crash, reconcile expired and pending identities before accepting
traffic. A failed/missed worker must not leave an unlimited credential active.
The [reference fixture](REFERENCE_PROVIDER.md) exercises these provider
obligations with real `yumed`. The synthetic model does not qualify that
enforcement or demonstrate a missing native capability. No account fields enter
native schema 1.

Native contracts: [configuration](https://github.com/FixCraft-Inc/yume/blob/main/docs/CONFIGURATION.md),
[operations](https://github.com/FixCraft-Inc/yume/blob/main/docs/OPERATIONS.md),
[sealed kit 1](https://github.com/FixCraft-Inc/yume/blob/main/docs/protocol/SEALED_KIT_1.md),
and [ABI](https://github.com/FixCraft-Inc/yume/blob/main/docs/ABI.md).

## Local state and transactional installation

Provider state is `Added`, `Authorizing` (only with auth), `Authorized`,
`Installing`, `Ready`, `Suspended` or `Removed`. Public provisioning starts
Authorized after descriptor validation. Each installed grant also has its own
expiry and generation. A provider becoming Suspended cannot be bypassed by
selecting an old cached grant. `Removed` is terminal for that enrollment epoch.

| From / event | Result |
| --- | --- |
| Added / start browser | Authorizing, bounded single pending attempt |
| Authorizing / valid callback and token persistence | Authorized |
| Authorized or Ready / valid grant response | Installing, previous record retained |
| Installing / all validation and commit succeed | Ready, new generation published |
| Installing / failure, cancellation or pre-commit crash | Previous committed record, no staged grant usable |
| Ready / account access token expires | Transport remains leased, API requires account refresh |
| Ready / grant expires or revocation observed | Suspended, stop connections and refuse reuse |
| Authorized or Ready / issuer or provider refuses the account's sign-in | Added, account tokens and their grant forgotten, connections stopped |
| Any live state / removal | Removed, block new jobs before local cleanup |

Before committing a provider grant, open the sealed file through `YumeLibrary`,
check hash/size, native client role and full schema, then check app constraints.
All credential references must resolve to regular files inside the validated
kit, with no absolute/external paths, links or executable invocation. The consumer's mandatory native adapter enforces its exact service and local-adapter
policy; a role does not impose VPN service names on another application. Refuse
circuits, proxy settings, unexpected services, mismatched host/port, mismatched
server public-file hash or client fingerprint. Native handshake still verifies
TLS and composite server identity. Never let a provider overwrite local DNS,
routing consent, limits chosen by the user or end-to-end contact trust.
Validate the exact adapted configuration as `KitStore` does. Native kit/config
validation remains authoritative, the model's synthetic evidence is not a parser.

Each sign-in creates a fresh non-exportable P-256 Android Keystore proof key
for its enrollment epoch. Only its public coordinates enter a DPoP proof.
The encrypted account record stores DPoP tokens and the epoch selects the
private key; it never stores or exports that private key. A missing proof key
withholds API access and requires sign-in again. Preview records containing
legacy Bearer tokens keep their enrollment and installed grant, but discard
the account session and require sign-in again. No token-only fallback is used.

Store account tokens, provider metadata and grant bundles in a Keystore-backed,
backup-excluded vault. Keep local communication identity in its independent
owner. Stage encrypted immutable generations, sync their content, then atomically
publish one enrollment manifest that binds metadata and kit hashes. Publish
Ready only after that commit. Recovery selects either the old or complete new
generation, rechecks expiry/revocation, and discards uncommitted staging. After
the commit, a crash must retain the new generation. Directory durability and
actual process-death recovery need platform tests. Existing `KitStore` writes
the blob and index separately, and `VaultBlobFile` atomically replaces a single
blob. The separate `ProviderStore` owns the provider transaction described in
[the client guide](PROVIDER_CLIENT.md#storage-and-removal).

Refresh stops the old local connection before switching generations. A storage
failure preserves the old record but does not undo remote rotation. Once remote
revocation is known, the old record stays Suspended. Ambiguous remote status
also suspends reuse until reconciled. A valid replacement may resume only after
a complete commit. Never restore authority from an old backup or late callback.

Removal first persists a non-secret tombstone/epoch, cancels pending callbacks,
refresh and provisioning, stops the provider's connections, then attempts remote
grant and account-token revocation within a single 30 second budget. Local
removal succeeds offline. Delete the enrollment proof key even when remote
revocation or vault access fails. Erase provider tokens, kits and registrations and
report remote revocation as confirmed or unknown. A locked vault leaves a
tombstone and cleanup pending, with no connection allowed, until secure deletion
can finish after unlock. Do not retain a hidden token for retries. Retry material
requires separate informed consent. Removal preserves other providers, manual
kits, local contacts, communication identity and security/routing preferences.
Removing a provider, deleting an account and deleting local identity are separate
actions. Flash erasure is best effort, not a forensic erasure guarantee.

## Manual setup and recovery

Manual sealed-kit import remains available without adding or contacting a
provider. Obtain a kit from a self-hosted operator through a trusted channel,
obtain its code separately, open through the native library, check client role
and app constraints, and store with the existing vault. A provider failure must
not disable that entry point or automatically move traffic to a manual kit.
Manual kits have no invented account token, provider lease or automatic expiry.
Their operator revokes the actual native identity using ordinary tools.
Manual and provider records must have distinct ownership, so provider removal
cannot erase or silently reclassify a manual kit. Offline recovery keeps this
same validation and explicit selection, with no hidden provider or weaker TLS.

## Executable conformance

Run locally from this repository, using Python 3.10 or later:

```sh
python3 tools/test_provider_conformance.py
```

The [fixtures](../tools/fixtures/providers.json) use two unrelated `.example`
operators: one browser-authorized and one public-provisioning operator, plus
manual setup. All identifiers, fingerprints and OAuth values are synthetic.
The cases pin expected errors for malformed and oversized input, origin/issuer
and callback substitution, device/audience/selection mismatch, unsupported
capabilities (including mandatory device binding for browser sign-in),
capability-bound nullable country metadata, DPoP token type, delivered-code
provider/device/audience/grant bindings, stale caches, refresh rotation, replay, expiry, transactional
failure and late completion after removal. State assertions preserve the other
operator, manual kit and local communication identity. Negative-control runs
remove individual validation guards in temporary copies and must make these
same cases fail. The model performs no OAuth cryptography, native kit opening,
disk durability or real daemon enforcement. The host reference fixture in
[REFERENCE_PROVIDER](REFERENCE_PROVIDER.md) verifies actual ES256 DPoP signatures
through OpenSSL, including method, URL, time, access-token hash, replay and
refresh-family key binding. Its opening-code route checks the issuing family,
device and active grant. The Android live test requires the shared client to
fetch authenticated-provider codes itself; the host supplies only independent
expected bundle metadata. Native, platform storage and real daemon gates remain
separate from the offline model.

Protocol references: [native OAuth, RFC 8252](https://www.rfc-editor.org/rfc/rfc8252),
[OAuth security, RFC 9700](https://www.rfc-editor.org/rfc/rfc9700),
[issuer responses, RFC 9207](https://www.rfc-editor.org/rfc/rfc9207),
[resource indicators, RFC 8707](https://www.rfc-editor.org/rfc/rfc8707),
[DPoP, RFC 9449](https://www.rfc-editor.org/rfc/rfc9449).
