# Shared Android provider client

This Android library owns provider contract 1, enrollment state, browser
authorization, bounded HTTPS, transactional provider publication and exact
service selection. Both yume4a and Ito consume this source project. Neither
application is the other's dependency.

The module version is `VERSION`. It is independent of the provider protocol,
native ABI and either application version. All original source is covered by
the repository's `COPYRIGHT` and AGPL-3.0-or-later `LICENSE`. Dependencies keep
their own grants, recorded by each application's complete notice inventory.

## Consumer boundary

Use `ProviderClient` with a `ProviderStore`, `ProviderKitValidator` and a
connection-invalidation callback. The consumer supplies:

- `ProviderSecretBackend`, which owns encryption, keys and name-bound
  ciphertext under no-backup storage. `ProviderStore` alone owns provider
  epochs, manifests, tombstones, recovery and the exclusive writer lock.
- `ProviderKitValidator.Native`, which opens sealed kits and uses the public
  libyume ABI to validate schema and referenced credentials. Native identity
  must come from the private credentials, with no successful default adapter.
  Its mandatory `checkConsumerConfig` enforces the consuming application's
  exact native service and local-adapter policy; the shared library has no
  application service-name allowlist.
- `ServiceAdapter<S>`, which owns encrypted selection and transient native
  session staging. Close its session only after every native borrower returns.
- Optionally, a `ProviderProofKeys` owner. The default keeps one
  non-exportable Android Keystore P-256 key per enrollment epoch and creates a
  fresh one at each sign-in. Keys of removed epochs are deleted at removal and
  swept at the next start.
- An optional suspend `authorizationAccess` hook for local access admission.
  It defaults to immediate admission. After validating and consuming the exact
  browser callback, the client runs this hook in its cancellable provider lane
  before reading provider secrets. Ito waits for its app lock to allow access;
  yume4a retains its existing immediate admission and vault checks.

The original authorization attempt's 300-second wall and monotonic budgets also
bound that wait; receiving a callback does not restart either budget. The wait's
own timeout is a typed `timeout` refusal, while caller cancellation stays coroutine
cancellation. Original expiry and clock regression are checked again after
admission and before token exchange. The consumed exchange remains process-local
and its owned request buffer is cleared on success, failure or cancellation.
The same original deadline checks a token reply before assigning credentials
and at the store's existing commit checkpoints before publication.

Browser-authorized providers must require `device-bound-enrollment-1`. Their
account tokens are DPoP-bound (RFC 9449): the code exchange, every refresh and
every catalog, grant, kit, opening-code and provider revocation request carries a fresh
ES256 proof for its exact method and URL. A copied token is useless without the
device key. The bound device fetches its grant's opening code from the provider
API itself, so sign-in and server selection complete enrollment. The
`openingCode` callback of `install` is used only for anonymous providers, whose
code still arrives through a separate channel. Bearer account records written
before device binding are not restored, so those enrollments sign in again.
A provider without device binding fails sign-in with `unsupported`.

`ProviderFile(path, executable, bytes)` and `ProviderBundleCodec` carry the
one bounded stored-bundle layout. Owned plaintext arrays must be cleared after
their final borrower. Provider bundles never become manual catalog entries.
The library has no JNI, private C++ include, native link, VPN, Compose screen,
application resource or business-policy dependency.

`ServiceController<S>` owns one process's operation and browser scope. Supply
the exact consumer role and a client factory. yume4a uses `vpn`, Ito uses
`relay` for named bounded data. The role is a checked part of each persisted
`ServiceSelection` and acquired lease. No older selection parser or role
fallback exists. `relay` defines no calling, signaling or media protocol.

The consuming application declares the no-display `ProviderCallbackActivity`
with its exact callback association and exports it only for an explicit public
registration. This library removes AppAuth's state-saving management activities.
`ProviderAuthorization` still requires Android's verified, enabled app link
and exact application resolution before opening the browser. It opens a Custom
Tab when the selected browser supports one, otherwise that browser itself.
After a live process owner accepts a callback, the callback activity returns
to the application's launcher activity, which closes the Custom Tab above it.

## Source dependency and provenance

The source project can be selected by `projectDir` in another Gradle build.
Its build script does not read an application module, a root version catalog
or sibling native checkout. Both consumers use AGP 9.1.0. The library and
yume4a target Java 11; Ito targets Java 17.

Run `python3 tools/provider_source_manifest.py --root provider-library` to
obtain the complete source binding. Supply its `source_sha256` as
`-PyumeProviderSourceSha256=...` and its `version` as
`-PyumeProviderVersion=...` on every build. The library refuses missing or
mismatched bindings. It packages `assets/provider-library/provenance.json`
with exact source modes, hashes, version and source aggregate. Qualification
also binds the repository HEAD/dirty state, APK and native libraries.

Library source and app adapter tests are distinct. Generic codec/deadline JVM
tests and browser/HTTP instrumentation live in this module. The test-fixture
source set supplies a narrow callback-race seam and is absent from production
APKs. yume4a retains its real vault, JNI, process-death, session and VPN flow
tests. Ito owns equivalent adapter and complete-flow tests for its lifecycle.

All Android builds and instrumentation run on the approved build host.
