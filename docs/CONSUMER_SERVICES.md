# Services and manual kits

Use the Connect screen to add an optional provider or import a manual kit. The
same application supports both paths in simple and Advanced modes. Opening the
app reads local enrollment state without contacting a built-in provider.

## Add a service

1. Choose **Add service**, enter the provider's canonical HTTPS origin and, for
   browser sign-in, its separately accepted issuer origin. Confirm consent to
   use those origins for VPN access.
2. Review the returned service label, provider and issuer. A label is display
   data, not proof of server authentication. If the service requires an account,
   choose **Sign in with browser** and complete authorization in the external
   browser. Browser sign-in requires the registration below.
3. Choose **Load servers** to request the enrollment's entitled catalog. An empty
   list means that enrollment has no available servers. Cached entries do not
   establish current entitlement, and provisioning rechecks catalog freshness.
4. Choose **Provision or refresh** for the intended server. Device-bound browser
   enrollment obtains the opening code automatically through its proof-bound
   API session. Only anonymous providers require a code from the operator's
   separate trusted channel. The native opener and credential validator check
   the downloaded kit before the provider store publishes it.
5. Choose **Use this server**, then **Connect** on Overview. Android's notification,
   battery and VPN consent steps remain owned by the existing connection flow.

The current client stores one installed server per provider enrollment. Refresh
keeps the same service and server and rotates the grant. To use another server
from that provider, remove the service and add it again. It does not silently
switch servers or providers after an error.

Manual kits remain below Services on Connect. Import the operator's sealed file
and its separately supplied code, select the kit, then connect. This path needs
no provider account, descriptor request or provider clock. Provider enrollment
never copies credentials into the manual-kit index. Advanced mode adds grant,
native client identity, expected server key and transport expiry details. DNS,
IPv6 and manual-kit tuning remain in their existing settings owners. Selecting
or provisioning a service does not overwrite those preferences.

## Browser registration

No provider, issuer, public client ID or callback is configured by default.
Manual kits and anonymous providers remain available without browser
registration. A consuming build supplies both Gradle properties:

```text
-PyumeProviderClientId=operator-registered-public-client
-PyumeProviderCallback=https://app.example.org/yume/oauth/callback
```

A distribution may also set `-PyumeSuggestedProvider=https://provider.example.org`
and `-PyumeSuggestedIssuer=https://account.example.org`. Both default to empty.
They must be canonical HTTPS origins; an issuer suggestion also requires a
provider suggestion and registered callback. They prefill the editable Add
service form, which still requires explicit consent before any request.
Server lists show the operator-declared country in the chosen application
language when supplied, followed by the server label. Advanced mode shows
the endpoint address. Country metadata is not evidence of traffic egress.

These illustrative values are not a working registration. The callback must
use HTTPS, its exact app-controlled host and the fixed `/yume/oauth/callback`
path, with no port, userinfo, query or fragment. The public client ID and
callback must match the issuer and provider descriptor exactly. Register the
actual variant's application ID, including `.agent` for an agent build, and
associate the callback host with the actual APK signer through
`/.well-known/assetlinks.json`. A debug signer association does not register a
release APK.

The build points the callback intent filter at the configured host and exports
its receiver only when both properties are configured. With empty defaults, the receiver is
unexported and its host placeholder is `unregistered.invalid`. Before opening
the browser, the client requires Android to report the host verified, handling
enabled and the exact callback resolved to this application. Typing an issuer
does not create that registration. The reusable client's
[browser checks](PROVIDER_CLIENT.md#browser-authentication) still apply.

## Ownership and selection

`YumeApplication` owns one process-scoped shared `ServiceController<KitSession>`.
`YumeServiceAdapter` supplies its existing encrypted selection and private kit
session owners, and `YumeProviderBackend` supplies its vault encryption. Those
adapters retain only application context. The controller owns the reusable client, exclusive provider-store
writer, callback registration and operation scope. Explicit application-host
teardown closes those resources. Android may kill the process without calling
`onTerminate`; pending browser attempts and opening-code prompts are not
serialized or resumed after restart. The controller permits one browser attempt
at a time. Another attempt refuses until the current one completes or is
cancelled, and callback cleanup stays bound to that attempt.

`ServiceSelection` records the provider origin, enrollment epoch, device,
service, server, grant, explicit role, expected native client identity, server-public-file hash,
host and port. The encrypted `service-selection` vault blob keeps this binding
or the explicit manual-selection marker, independently of manual kits and
application settings. Display state alone grants no authority. Selection and
VPN startup reacquire a lease and compare every authority binding. A newer
selection wins over a delayed operation, and changing a selection while Android
is asking for VPN permission refuses that pending connect action.

yume4a explicitly requests and accepts only `vpn`. Ito's independent consumer
uses `relay` for its named bounded data transport. The shared role check cannot
turn one consumer's grant into the other's authority. A saved selection without
its role is refused and has no manual fallback. The shared module's independent
version, exact source dependency and build binding are described in its
[source guide](../provider-library/README.md#source-dependency-and-provenance).

The VPN service keeps the non-secret binding for a restart and reopens it through
the provider owner. It never saves a plaintext lease or opening code in an
intent or restart preferences. Provider bundles use `KitStore`'s existing
private session materialization and cleanup without becoming manual imports.
Native YUME owns the authenticated connection and peer checks through the same
`YumeClient` path used by a manual kit.

Each provider run owns a stop permit with UTC expiry and a monotonic deadline.
The service checks it during startup and throughout connection and reconnection.
Expiry, unavailable trusted time, clock rollback, vault lock or provider
invalidation stops that run through the existing cancellation and native-stop
path. The guard belongs to the captured run and cannot stop a newer run. A
restart must acquire authority again. It cannot reuse an expired, removed or
mismatched grant.

Account-token and anonymous-device API expiry remain independent of transport
expiry. Account access-token expiry, or a refresh that gets no answer, does not
itself end an existing valid transport lease. When the issuer or provider
refuses the sign-in itself, its grant ends with it: the app shows the service
as signed out, and after signing in again the user chooses a server. Provisioning suspends the prior grant
before a potentially ambiguous remote rotation. A failed installation publishes
no usable replacement. One explicit retry can reuse the exact pending grant
request. Codes remain process-local and owned mutable buffers are cleared when
released.

## Failure and removal

Failures leave the selected provider and routing choices unchanged. Stale or
empty catalogs, missing trusted time, expired API credentials, unavailable
servers and locked or unreadable storage have visible outcomes. Reload local
services, explicitly load a fresh catalog, sign in again or use a manual kit as
appropriate. A lost or cancelled browser attempt requires a new attempt. An
unavailable selected server does not trigger a fallback to another server,
provider or manual kit.

Choose **Remove service** and confirm its origin to remove that enrollment.
Removal invalidates its run permits and pending operations before local
deletion, then attempts bounded remote revocation. It works offline and reports
that remote revocation may remain unknown. Other providers, manual kits and
privacy preferences retain their independent owners. A removed selection stays
unusable until the user explicitly selects another service or manual kit. Until
then the app shows no selected service, never a manual kit, and has nothing to
connect to.

The protocol's [storage and removal contract](PROVIDERS.md#local-state-and-transactional-installation)
and the [client's native validation boundary](PROVIDER_CLIENT.md#native-validation-boundary)
describe the durable publication and cleanup limits. Device and complete-flow
qualification are separate from these implementation contracts.

## Generic flow qualification

`ServiceLiveIntegrationTest` is an explicit host-fixture gate excluded from the
standard instrumentation runner. Its optional `traffic` handoff enables the
complete flow: actual document import and browser return, both operators,
live grant rotation, account/API expiry and refresh, server revocation of an
established stream, restart, transport expiry and offline removal. It compares
manual-kit contents and application preferences across those operations.

The host supplies one non-loopback numeric destination, separate TCP and UDP
echo ports, expected native peer fingerprints and a bounded expiry wait. Test
sockets bind to Android's active VPN network. Exact echoed payloads, increasing
native payload counters and unchanged peer/run/session bindings must all pass.
A Connected label, handshake or loopback exchange cannot satisfy this gate.
Fixture policy permits only the selected destination and resolver in addition
to loopback. Separate HTTP requests check server authorization independently
of the UI. Host fixtures, credentials, registration, CA and public association
are temporary and must be restored or removed after execution.

This gate qualifies only its exact binaries and tested platform. Physical-device,
TalkBack/cutout, network-handover, ARM64 and signed-release results need their
own evidence and remain separate from an emulator flow result.
