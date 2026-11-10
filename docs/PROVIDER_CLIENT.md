# Android provider client

The [`provider-library`](../provider-library/README.md) Android source project
implements [provider contract 1](PROVIDERS.md). Its provider and consumer
packages have no VPN, screen, private account service or business-policy
dependency. The shared `ServiceController` owns selection and lease state.
Application adapters connect it to yume4a's vault, native session and VPN owner.
Ito consumes the same module through its own adapters. Manual imports continue
through their independent stores.

`ProviderClient` coordinates descriptor/catalog requests, account authorization,
anonymous device credentials, grant staging, refresh and removal. The caller
supplies an accepted issuer, trusted clock, connection-stop hook and native kit
adapter. Browser authorization additionally requires a public registration.
No provider or callback is selected by default.
`ProviderCodec` owns the closed bounded network formats. Kit downloads carry
the grant request's validated device ID in `X-Device-ID`, alongside its
origin-bound credential. Unknown required
capabilities, including OIDC, fail visibly. A browser-authorized provider
without `device-bound-enrollment-1` fails sign-in with `unsupported`.

`ProviderProof` builds the RFC 9449 proofs of
[device-bound enrollment](PROVIDERS.md#device-bound-enrollment).
`AndroidProviderProofKeys` keeps one non-exportable Android Keystore P-256 key
per enrollment epoch and replaces it at each sign-in. The key needs no user
authentication: a consumer's access hook admits provider work before any
request is signed. It adds no hardware-authentication claim. After the token
exchange, every refresh and API request carries a fresh proof bound to that
key. The client fetches a grant's opening code from the provider API and
validates the kit with it at once. The code briefly exists as a JVM string
while its JSON reply is parsed, as account tokens do. Its character array is
cleared after validation. Bearer account records from before device binding
are not restored: the enrollment and its grant stay, and the user signs in
again. A missing key has the same effect.
Creation and local removal share the enrollment's publication monitor, so a
cancelled callback cannot create a key after that epoch has been removed.
Standard OAuth token revocation retains its token/client-ID contract; provider
resource revocation uses a proof-bound API request.

## Browser authentication

AppAuth for Android 0.11.1 constructs Authorization Code requests with S256,
resource binding and a fresh 32-byte state/verifier, and selects the browser.
The sign-in page opens in a Custom Tab of that browser when it supports one,
otherwise in the browser itself. Both share the browser's account state and
keep passwords out of the application. The wrapper does not use AppAuth's management Activity: that Activity
serializes its authorization request, including the verifier, into saved state.
Pending attempts instead live only in the process. Restart requires a new
attempt. Exact callback, issuer, state, registration, resource and deadlines are
checked before a single-use code is exchanged. Ambiguous token exchange or
refresh loss requires sign-in again. A refresh whose connection could not even
be opened sent nothing, so it keeps the sign-in. Every provider request opens
its own connection: a pooled one can outlive the network or VPN tunnel it was
opened through.

A refused sign-in ends more than the tokens. When the issuer refuses a refresh
token or authorization code (`invalid_grant`), or a provider answers
`unauthorized` to the account's own credential, the client forgets the account
tokens and the grants they obtained, then stops their connections. The
provider closes a grant together with the sign-in that obtained it. The chosen
server stays, so after signing in again the next connection acquires a fresh
grant for it. A refresh that gets no answer forgets only the spent tokens and
leaves the lease running.

`ServiceController.choose` records a server from the loaded catalog for its
enrollment and selects that provider's `ServiceRoute`, without the network. The
stored route names only provider, epoch and role; the chosen server lives in the
enrollment record. A connection calls `prepare(route, renew)`, which returns a
lease-bound `ServiceSelection`. With `renew` false, a starting connection takes
any live grant for the chosen server unless it ends within two minutes, so a
blocked provider API does not stop a reconnect. Otherwise a grant is reused
until `Grant.renewAt`, the later of the provider's `refresh_after` and half the
lease. After that, and for any other server, a fresh grant is issued while the
current one stays usable; the replaced grant is revoked about 30 seconds later
or retried until it expires. A renewal the provider cannot answer returns the
live grant with its refusal. `renewAfter` reports when a running connection
should call `prepare` again: only device-bound providers qualify, since an
anonymous provider's opening code is typed by hand.

A consumer may supply a suspend `authorizationAccess` hook. Callback validation
and single-use consumption happen first; then the provider operation lane owns
the admission wait before any secret-store snapshot. Cancel, removal and close
can cancel that wait. Its maximum is the original attempt's remaining
300-second wall/monotonic budget, and expiry and clock regression are rechecked
after admission and before the token request. The wait's own timeout is a typed
`timeout` refusal; external coroutine cancellation is preserved. No callback or
exchange is persisted. yume4a uses the default immediate hook and retains its
vault access checks. Ito waits for its optional app lock without exempting
provider storage from that lock.
The original deadline also checks a token reply before credentials are assigned
and at the existing store commit checkpoints before publication. A late reply
cannot become a newly authorized account.

`ProviderCallbacks.attach` lets the consumer provide its process-local callback
receiver and coroutine scope. The receiver must call `ProviderClient.callback`
off the main thread and handle its typed failure. `ProviderCallbackActivity`
clears the incoming URI at entry and saves no callback state. When a live
receiver accepts the callback, it starts the application's launcher activity
with `CLEAR_TOP | SINGLE_TOP`, which closes a Custom Tab left above it in the
task. A cold callback with no receiver is ignored and opens nothing. The default build
leaves that Activity unexported. An explicitly configured consuming build uses
the public client ID and exact HTTPS callback supplied by
[`yumeProviderClientId` and `yumeProviderCallback`](CONSUMER_SERVICES.md#browser-registration).
The build supplies the matching verified intent filter. The operator must also
publish the actual APK signer's association and obtain the issuer's matching
public client registration before browser authorization can succeed.
`unregistered.invalid` is the disabled build placeholder, not a registration.
Debug builds permit user-installed CAs only for the three fixed
`{alpha,beta,issuer}.127.0.0.1.nip.io` loopback fixture names, through
`provider_test_network_security.xml`. Install a fresh temporary CA through
Android Settings and remove that certificate after the run. Production uses
ordinary system trust; no client TLS or hostname check is overridden.

Before opening a browser, the wrapper requires the installed application's
domain to be OS-verified and enabled, and the exact callback to resolve to that
application. User-selected domain handling alone is insufficient. A failure
here is the local refusal `link_unverified`, kept apart from `binding` because
the person can act on it: `ProviderAppLinks.settingsIntent` opens Android's
link settings for the application. This check requires Android 12 or later. The default trusted-clock adapter uses Android's
network time on Android 13 or later and suspends when it is unavailable. An
older platform needs an independently qualified trusted-clock adapter; manual
kit import has no provider-clock dependency.

## Storage and removal

`ProviderStore` receives a `ProviderSecretBackend` and a no-backup control
directory from its consumer. yume4a's `YumeProviderBackend` reuses
`AppVaultManager`'s Keystore-wrapped data key and optional password gate. The
backend contract requires name-bound encryption and atomic, synced record
replacement. Neither layer treats a locked or corrupt vault as an empty provider.
Encrypted immutable records are synced before an encrypted enrollment manifest
is atomically replaced and its directory synced. The store holds one exclusive
writer lock. Recovery verifies referenced record hashes and removes uncommitted
provider staging. A failure after publication is ambiguous to the caller;
recovery reads the complete committed generation and the caller must withhold
Ready on the failed operation.

A separate non-secret, backup-excluded active-epoch index makes removal durable
even while the vault is locked. Removing an origin invalidates its epoch before
deleting only its encrypted records. Re-adding it creates a new epoch. The
client cancels its jobs and callbacks, stops its connections and uses any
available credential only for a bounded immediate revocation attempt, signed
with the enrollment's proof key. It then deletes that key. Offline
removal retains no retry credential, and remote revocation may remain unknown.
Other providers, manual kits and application preferences have separate owners.
Flash erasure is best effort.

Account-token and anonymous-device API deadlines remain separate from transport
leases. A lease exposes both its UTC expiry and local monotonic deadline. Its
consumer must stop the corresponding connection at that deadline and must not
reuse a closed bundle. Untrusted time, rollback, known revocation and ambiguous
remote rotation suspend reuse. Descriptor revalidation suspends on a changed or
refused descriptor, but not when the provider is unreachable or rate limited:
an outage says nothing about the provider's identity, and the lease keeps its
own deadline. A failed grant request retains its exact
idempotency body for explicit retries until the provider answers it
definitively, rather than minting another
identity under a new request ID.

The consumer binds a selected server to its provider, enrollment epoch, device,
service, server, grant, role, local native identity and expected server key. A lease
returns those bindings with its bundle and deadlines. Listing local enrollments
does not contact a provider or establish current transport authority. The VPN
owner reacquires the exact lease for each run and observes its stop permit while
starting, connected and reconnecting. See
[consumer ownership](CONSUMER_SERVICES.md#ownership-and-selection).

## Native validation boundary

`ProviderKitValidator` checks the real sealed-file hash/size, opens it through
the consumer's mandatory native adapter, checks original and consumer-compatible
native configuration, confines all
credential references to its in-memory regular files, and binds the selected
host, port and server-public-file hash. It refuses circuits, proxies and fixed
dial addresses. Its mandatory consumer adapter enforces exact native services
and local adapters before credential verification. yume4a requires exactly
`tcp`/`stream` and `udp`/`packet`, with either no local adapter or one `socks5`
adapter bound to `tcp` and optional `udp_service=udp`; device adaptation retains
its existing controlled listener policy. Other consumers own their separate
service contracts. Codes and owned plaintext buffers are
wiped on exit. The code handoff is caller-supplied; production reads no fixture
handoff file and has no TLS/DNS/CA bypass.

`YumeProviderNative.validator(context)` supplies yume4a's supported adapter.
Its `ProviderNativeIdentityVerifier` writes only the six referenced credentials into a random, private directory
under `noBackupFilesDir`, with directories created as 0700 and files as 0600.
It never executes kit files. The synchronous JNI call uses
`yume_config_validate_client_credentials` to load the actual credentials,
check the composite private/public key pairs, and derive the local fingerprint
from the private identity. It creates no endpoint, resolver, VPN or connection.
The validator compares that fingerprint with the grant before returning a
bundle. A hash of an accompanying public file cannot establish this identity.

The C ABI resolves references using its runtime's configuration base directory;
it retains schema 1's absolute-path behavior. The Android adapter separately
requires unique, safe relative references from the opened in-memory bundle.
Callers of the general native API must keep credential files and their ancestor
directories private and stable throughout the call. Validation establishes
local credential consistency, not server authentication, admission or lease
validity. The selected server's public-file hash remains a separate binding.

Owned temporary credentials are overwritten and removed before success,
failure or thread cancellation returns. Cleanup failure prevents success.
Overwriting is best effort on flash and does not erase VM or filesystem copies.
Abrupt process termination can leave the private validation directory behind;
this adapter does not promise crash-time erasure or automatic orphan recovery.
The enclosing client still publishes only a successfully validated bundle.

## Checks

`ProviderCodecTest` covers closed bounded formats and contract bindings on the
JVM. `ServiceRoleTest` rejects missing roles and role substitution in persisted
selections and acquired leases. `ProviderBundleCodecTest` preserves the stored
bundle byte layout. The generic codec, deadline, browser and HTTP tests live
in `provider-library`; a test-only fixture exposes callback-race construction
without adding a production bypass. Android instrumentation exercises real AppAuth/Uri behavior, callback
forgery/replay, issuer mix-up, cancellation, clock failure, actual Keystore
storage, removal isolation and publication failures. Native policy tests label
their identity stub explicitly; they do not qualify the native fingerprint
adapter. `ProviderStoreProcessTest` has explicit crash/recovery phases that kill
the real application process before and after manifest publication.

Build and run these tests on the build host, following the repository guide.
A request-construction test, reopened store or synthetic identity stub does not
qualify OS/browser app-link delivery, actual process death, both live operators
or a real native identity. Those are separate acceptance gates.

`ProviderNativeIdentityTest` is a separate integration gate using the real JNI
adapter. The standard instrumentation runner excludes classes annotated
`RequiresNativeFixtures`. For this gate, privately seed
`no_backup/provider-native-fixtures` in the target test application with
`alpha`, `beta` and `manual` sets of `.kit`, `.code` and `.json` files. Generate
the first two with independent reference `Operator` instances and the third
with native init/seal/import. Metadata records `host`, `port`,
`server_key_sha256`, `client_identity`, `kit_sha256`, `kit_size` and a `files`
map of relative path to SHA-256. Codes, kits and keys are ephemeral test data;
never commit or log them. Run the class explicitly with AndroidJUnitRunner
without the default annotation exclusion, then remove the fixture handoff and
test application. All cases must execute without skips. This gate verifies
private-key-derived identity, invalid credentials and bindings, private-file
checks, and cleanup after failure/cancellation. It does not qualify browser
HTTPS delivery or live enrollment.

`ProviderLiveIntegrationTest` is another explicit fixture gate, excluded from
the standard runner by the same annotation. The host supplies a private
`no_backup/provider-live-handoff/config.json` with exact alpha, beta, issuer,
client and callback registration, a separately generated manual kit/code, and
expected-file metadata through a separate local handoff. The device-bound
alpha enrollment receives its code only through the proof-bound provider API;
the host supplies code only for the independent anonymous beta enrollment.
The test uses the production HTTP transport, Android network clock, real native
validator, provider vault and independent `KitStore`. Its browser starts only
after OS verification and must return through `ProviderCallbackActivity`.
Run the test explicitly against fresh independent services. The host must
stop the requested fixture services, remove the handoff, uninstall the test
apps and restore emulator trust/settings. A synthetic transport or ADB callback
intent cannot satisfy this gate.
