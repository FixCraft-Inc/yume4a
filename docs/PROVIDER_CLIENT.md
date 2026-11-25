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
live grant with its refusal, and the next attempt waits a quarter of the lease
left, from 30 seconds to 5 minutes, on the monotonic clock. `renewal` returns a
`Renewal` whose `due()` tells a running connection when to call `prepare` again,
judged on [trusted time](#trusted-time) and never the device's wall clock.
Only device-bound providers qualify, since an anonymous provider's opening code
is typed by hand.

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
leaves that Activity disabled and unexported. An explicitly configured consuming build uses
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

Before opening a browser, `ProviderAppLinks.verify` requires the exact callback
to reach this application alone. On Android 12 (API 31) and later, Android must
report the callback host verified and link handling enabled for the
application, and the callback must resolve to it. User-selected domain
handling alone is insufficient there. Android 9 to 11 have no query for
verification state, so there the callback must resolve to this application,
which is true when Android verified the link at install or the person chose
this application for it. The library's manifest declares the https `VIEW`
query, so on Android 11 browsers are visible to that resolution and cannot be
mistaken for this application. Below Android 12 a callback that another
application received anyway is protected as RFC 8252 describes: the code is
useless without this attempt's PKCE verifier, its `state` must match, and the
token exchange is bound to the device's DPoP key. A failure is the local
refusal `link_unverified`, kept apart from `binding` because the person can
act on it: `ProviderAppLinks.settingsIntent` opens Android's link settings for
the application. Manual kit import has no link or provider-clock dependency.

## Trusted time

The client needs trusted time to judge descriptors, catalogs, grants, tokens,
authorization attempts and DPoP proofs. A device wall clock alone is never
trusted time. `AndroidProviderClock` is the one process-wide clock, which
`ProviderClient`, `ProviderAuthorization` and `ServiceController` use by
default, so they share one reading.

On Android 13 (API 33) and later it reads Android's network time. Below
Android 13 it is anchored to the provider's own responses. `ProviderHttp`
reports the `Date` header of every response that passed TLS and its header
checks, before the body is read, together with `elapsedRealtime` just after the
connection was established and when the status line arrived. The provider
wrote that `Date` inside this interval and truncated it to the second, so the
anchor takes it as the time at arrival and never runs ahead of the provider's
clock. A response whose round trip took more than 10 seconds, or whose `Date`
is not an RFC 9110 IMF-fixdate, is ignored. The bound keeps the anchor's lag
well inside the issuer's 60-second proof window. A later response can move the
anchor forward, never back, because `ProviderClient` refuses any regression as
`stale`. Between responses the reading advances with `elapsedRealtime`. The
anchor is saved under `noBackupFilesDir` with `Settings.Global.BOOT_COUNT`, so
a process that restarts in the same boot keeps it and a reboot discards it.

Feeding the anchor before the body is decoded means a body's own time checks
run against a reading no earlier than that response's `Date`. The client
applies the timestamp bounds of [provider contract 1](PROVIDERS.md) with no
clock tolerance and refuses a value outside them as `stale`. An `issued_at`
must not be later than the reading, a descriptor's `expires_at` must be at
most 3600 seconds after it, and a catalog's `expires_at` at most 300 seconds
after its `issued_at`. So on the anchored clock a provider passes when it
dates every `issued_at` no later than its `Date` and sets a descriptor's
`expires_at` at most 3600 seconds after that `Date`. Android's network time is
a separate clock, so a provider should keep a margin on these bounds. The
[reference provider](REFERENCE_PROVIDER.md#isolated-execution) dates a
catalog's `issued_at` one second early and a descriptor's `expires_at` 3599
seconds ahead. Time is trusted from a provider as its other answers are. One
whose clock runs ahead moves the shared reading ahead for every provider until
the next reboot.

Without trusted time, an operation that talks to a provider first fetches that
provider's descriptor, which anchors the clock. A local operation, such as
choosing a server, refuses at once with the local code `clock_unavailable`
rather than waiting on the network. Missing time never stops a running
connection by itself. A detected regression does, as `stale`.

After a reboot an Android 9 to 12 phone has no trusted time until a provider
answers. If none can be reached when a connection starts or renews, which a
blocked API can take up to the 10-second connect deadline to show, `prepare`
uses the chosen server's held grant, unless the trusted time last saved with
the enrollment is already past the grant's expiry, and returns it with the
renewal refusal `clock_unavailable`. That lease's local deadline is the grant's
expiry minus the saved time, measured on `elapsedRealtime`, so it can run late.
The node enforces the grant's expiry and revocation itself and refuses an
expired grant. Without trusted time a renewal is due at once, because its first
request restores time. Through a running tunnel that request reaches a blocked
API, and renewal then works as usual. If no provider answers, the connection
keeps its grant and tries again after 30 seconds. A run permit without a trusted
reading checks only its monotonic deadline.

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
reuse a closed bundle. Clock rollback, known revocation and ambiguous remote
rotation suspend reuse. Missing trusted time follows
[the reboot rule](#trusted-time). Descriptor revalidation suspends on a changed
or refused descriptor, but not when the provider is unreachable or rate limited:
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
wiped on exit. The caller supplies the opening code. Production code reads no
test fixture file and has no TLS, DNS or CA bypass.

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
JVM. `ProviderTimeAnchorTest` covers the trusted-time anchor's round-trip
bound, date format, forward-only correction and boot binding, and
`ProviderAppLinksTest` the callback rule's decision on each Android release.
`ServiceRoleTest` rejects missing roles and role substitution in persisted
selections and acquired leases. `ProviderBundleCodecTest` preserves the stored
bundle byte layout. The generic codec, deadline, browser and HTTP tests live
in `provider-library`; a test-only fixture exposes callback-race construction
without adding a production bypass. Android instrumentation exercises real AppAuth/Uri behavior, callback
forgery/replay, issuer mix-up, cancellation, clock failure, actual Keystore
storage, removal isolation and publication failures. Native policy tests label
their identity stub explicitly; they do not qualify the native fingerprint
adapter. `ProviderStoreProcessTest` has explicit crash/recovery phases that kill
the real application process before and after manifest publication.

Use the [build prerequisites](../README.md#build) when running these tests.
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
map of relative path to SHA-256. Codes, kits and keys are throwaway test data.
Never commit or log them. Run the class explicitly with AndroidJUnitRunner
without the default annotation exclusion, then remove the fixture directory and
the test application. All cases must execute without skips. This gate verifies
private-key-derived identity, invalid credentials and bindings, private-file
checks, and cleanup after failure/cancellation. It does not qualify browser
HTTPS delivery or live enrollment.

`ProviderLiveIntegrationTest` is an optional end-to-end test, excluded from the
standard runner by the same annotation. It needs HTTPS provider services that
you run yourself, such as the [reference provider](REFERENCE_PROVIDER.md): a
device-bound provider `alpha` with its issuer, an anonymous provider `beta`, and
a public client registration and callback for the test application. It also
needs a host-side program that writes the test's inputs and answers its
requests during the run. That program is not part of this repository. The
inputs go in the test application's private
`no_backup/provider-live-handoff/` directory: `config.json` with the exact
alpha, beta and issuer origins, client ID and callback, a separately generated
manual kit and code, and the expected file metadata. The alpha enrollment
receives its code only through the proof-bound provider API. The host supplies
a code only for the anonymous beta enrollment. The test uses the production
HTTP transport, Android network clock, real native validator, provider vault
and independent `KitStore`. Its browser starts only after OS verification and
must return through `ProviderCallbackActivity`. Run the test explicitly against
fresh services. Afterwards stop those services, delete that directory,
uninstall the test apps and restore emulator trust and settings. A synthetic
transport or ADB callback intent cannot satisfy this test.
