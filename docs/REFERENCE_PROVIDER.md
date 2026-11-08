# Isolated reference provider

The host-only tools below exercise [provider contract 1](PROVIDERS.md) with
real `yume-setup`, `yume` and `yumed`. They are a synthetic conformance fixture,
with no Android runtime or account backend dependency. Run native integration
on a build host. Python-only checks need Python 3.10 or later.

`tools/provider_operator.py` owns provisioning. `tools/provider_supervisor.py`
owns the daemon process and its independent lease deadline. The local HTTPS
service and test issuer live in `tools/reference_provider.py`. The integration
runner is `tools/test_reference_provider.py`. These tools are outside the app
and native transport build graphs.

## Ownership and recovery

Each synthetic operator has its own server keys, admission key, API origin,
credential registry, SQLite journal and exclusive writer lock. Alpha uses a
local public-client issuer with device-bound DPoP token families. Beta registers a bounded anonymous device with a
separate API credential. Both advertise `server-location-1`, with synthetic `US` server metadata for
display, and ordinary TCP and UDP access. A catalog
label does not narrow the native services. A non-issued control identity keeps
ordinary last-client removal possible.

Each provider process selects one exact application provisioning role with
`--role vpn` or `--role relay`; omitting the option selects `vpn`. The operator
journal binds that role before native initialization. Descriptors, catalogs,
grant requests and rotations use that same role. Restart with a different,
missing or invalid saved role refuses before the daemon supervisor starts;
an already initialized store without a role binding also refuses. Use a fresh
private fixture root for a new role. The issuer does not accept `--role`.
Both roles provision the same bounded TCP and UDP data services; selecting
`relay` does not implement signaling, calls, media or a new native transport.

The adapter accepts trusted operator paths and exact executable paths, never
commands or paths supplied by an API caller. A private fixture directory must
be owned by the running user and have mode 0700. Only one service owns it.
SQLite uses FULL synchronization. A pending request records its exact body,
principal, deterministic native client name and original deadline before native
publication. The journal is not a transaction spanning the native store and
kit files.

An exit-3 result or a crash is reconciled from actual store entries and bundle
files. A complete published bundle can finish the original request. Incomplete
publication is removed before serving again and that request remains failed.
An identical request cannot mint another identity or extend its deadline.
Changed bodies conflict. Idempotency records last 86400 seconds, with at most
64 per principal. Refresh removes the previous identity and confirms reload
before returning the replacement. Removal and revocation are retriable.

The supervisor starts the daemon only after restart reconciliation. It arms
the earliest pending or active lease before provisioning. It checks both wall
and monotonic deadlines, refuses clock rollback, and kills its owned daemon
process group if the provider pipe closes or enforcement is still outstanding
250 ms before the advertised deadline. The expiry worker normally removes
identities one second before expiry. Early closure is deliberate. There is no
extension past expiry. A killed or stopped worker cannot extend a grant.
Failure of the supervisor itself is outside this fixture's process-failure
model and requires an external service supervisor in a deployment.

Reload confirmation requires a new successful daemon outcome after the signal,
with serialized requests. A refused or missing outcome stops the daemon.
Integration assertions additionally observe actual authenticated traffic and
new admission. The adapter never calls a sent signal a successful revocation.

## Isolated execution

The runner takes explicit `--yume`, `--yumed`, `--setup` and `--source-root`
paths, and an existing private `--evidence` directory. It refuses binaries
outside the selected build directory. The native source and binaries must be
bound by the build-host driver before execution. It creates one private
temporary directory for keys, journals, sealed kits and the anonymous provider’s
separate offline opening-code handoff, and removes that owned directory on exit.
Alpha requires a fresh signed DPoP proof on every token and resource request,
and delivers its opening code only through the family/device/grant-bound
`POST /provider/v1/opening-code` route. The native runner consumes that route
before opening alpha’s kit. No generated
credential belongs beside these sources. Evidence contains hashes and outcomes,
with no opening code or credential.

The native runner also accepts `--role vpn|relay` (default `vpn`) and records
the selected role in its result. Run the affected cases separately for each
role. Its binding cases refuse wrong-role issuance, changed-body retries and
rotations without allocating native authority. Its restart case refuses a
changed operator role before accepting the original role again. The offline
`python3 tools/test_provider_roles.py` checks role persistence and the public
descriptor/catalog/request boundaries with native-command and supervisor
stubs; its synthetic grant replies do not qualify native provisioning or
traffic. Those require the build-host runner with exact bound binaries.

Descriptor expiry leaves one second below the one-hour maximum, and catalog
issuance is dated one second before the fixture's current whole second, with
expiry still bounded to 300 seconds from issuance. This fixture margin avoids
crossing strict client timestamp bounds under subsecond clock offsets. The
client still rejects future-issued or overlong metadata without clock tolerance.

HTTPS listeners bind only to 127.0.0.1 and use a temporary test CA. The fixture
client maps exact synthetic DNS names to that address while verifying TLS
hostname and CA normally. It never disables certificate verification. The
issuer accepts only the registered public client, fixed HTTPS callback,
provider resource, state and S256 challenge. Code exchange and refresh are
single use. Tokens have type `DPoP`; refreshing with another key revokes the
family. `/usr/bin/openssl` verifies the exact contract ES256 profile: closed
header and claims, P-256 public coordinates, 64-byte JWS signature, method/URL,
60-second issue-time window, resource `ath`, and one-use key/`jti`. Proofs are
bounded to 2048 bytes and the private replay table to 4096 live entries. Python
does not implement elliptic-curve operations. Python-only reference tests need
that OpenSSL executable as well. Use a fresh private fixture root after a
fixture schema change; old token databases are not migrated.

The callback response includes the exact issuer. The fixture
observes the callback URL without following it. It does not register an Android
app link or emulate OS verification. Those remain platform gates.

For Android qualification, `--client-id` and `--redirect-uri` explicitly select
the test public registration on both issuer and provider processes. Defaults
retain the synthetic sentinel above. A different callback must be a canonical
HTTPS URL, with no wildcard, query or fragment. The issuer persists the exact
registration and refuses a restart that changes it. Authorization, exchange and
refresh still require the registered client and callback exactly. Run
`python3 tools/test_provider_registration.py` for substitution and restart
controls. The issuer-only `--browser-continue` flag renders an escaped return link after
the same exact-bound authorization. A real browser click lets Android resolve
that HTTPS link; the automatic redirect remains the default. This flag changes
only fixture delivery, never code exchange, PKCE or registration checks.
Supplying these flags does not publish an Android signer association
or establish OS/browser delivery.

For Android packet-traffic qualification, the provider-only `--traffic-address`
option accepts one canonical numeric, non-loopback unicast IPv4 address owned by
the harness. It permits that exact `/32` in each daemon adapter's destination
policy, alongside `127.0.0.0/8`, with public destinations still disabled. The
optional `--traffic-dns-address` accepts one resolver through the same strict
validation and requires `--traffic-address`. It adds at most that resolver's
exact `/32`, without duplicating the traffic address. This lets live checks
retain the application's selected resolver while reaching fixture APIs through
the VPN. No hostname, network range, IPv6, unspecified, loopback, multicast,
reserved or link-local address is accepted. The default remains loopback-only.
These options are trusted host configuration and are never provider API fields.
An existing fixture root must retain the same exact destination policy on
restart. A changed requested or saved policy refuses startup before the daemon
supervisor starts and leaves that policy unchanged.

A live packet check must use a separate harness-owned listener at that address,
explicitly bind its Android sockets to the VPN network, match sent and received
payloads, and correlate them with the selected run's native traffic counters.
An emulator-loopback response or a Connected label cannot establish device-bridge
carriage. Run `python3 tools/test_provider_traffic.py` for the option's offline
validation and restart checks. Its native-command and supervisor stubs do not
qualify packet carriage or native policy enforcement.

Manual provisioning uses a third independent server and a separate sealed kit.
Import and traffic work without contacting either provider or the issuer.
Removing one provider preserves the other provider and the manual server.

Run the offline model separately:

```sh
python3 tools/test_provider_conformance.py
python3 tools/test_reference_provider.py
python3 tools/test_provider_registration.py
```

The native runner records each failed attempt and exits nonzero on any unmet
gate. Its negative controls use temporary source copies, never edits to a
developer's transport tree. A model or HTTP response alone cannot satisfy a
daemon enforcement assertion. Full Android builds, app UI, browser/app-link
verification, production deployment and physical devices are separate work.
