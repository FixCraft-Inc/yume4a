# YUME 0.2.0-dev6 Android transport synchronization

## Status

The Android VPN entrypoint now consumes the shared C++ YUME client and public
packet ABI. It is based on signed `origin/main` commit
`dd502c464b6a09c380d3faaeb8b52919dfd37636` from the sibling `~/yume`
checkout. “YUME 2.0” names the architecture generation; `0.2.0-dev6` is the
exact product and authenticated transport identity. AUTH v2, relay v2, C ABI
v1, and helper IPC v1 are independent schema versions.

Android 2.0 support is **not release-approved** until the remote build,
instrumentation, live packet traffic, lifecycle, and throughput gates below
pass on supported devices.

## Ownership boundary

- `libyume.so` owns exact-version negotiation, TLS 1.3, the HTTP/2 WebSocket
  carrier, authenticated handshake, ML-KEM-1024 + X25519 + PSK derivation,
  AES-256-GCM, AAD, independent directional ratchets, packet-bulk sequences,
  bounded native queues, cancellation, and buffer limits.
- `libyumeandroid.so` owns only opaque C handle lifetime, C-status/JSON
  translation, direct packet buffers, and the synchronous socket callback into
  `VpnService.protect(fd)`.
- Kotlin owns profile/UI state, private material staging, VPN permission and
  lifecycle, the Android TUN fd, IPv4 routes/DNS/MTU, direct batching buffers,
  worker joining, and status presentation.
- The generated BaseFWX Java tree remains for app file/share crypto. It is not
  part of the YUME transport and must not be expanded to reproduce transport
  crypto.

The historically named `anonym` config fields remain at the ABI boundary for
compatibility. The app describes this material as the **operator CA**: a valid
proof means the endpoint key was authorized by the CA selected by the user. It
does not prove that an independently administered host cannot inspect, retain,
or correlate traffic. A delegated server certificate should carry the operator
CA identity as issuer and the public server alias as subject; operators should
publish both CA/server SHA-256 fingerprints and the certificate serial.
The packaged FixCraft operator CA starts in the material list but is removable
and is never mandatory. An unassigned operator CA uses Android system TLS trust
(and an optional leaf pin); such a profile must disable required operator proof
and explicitly accept that the server may monitor traffic.

Complete browser fixtures are listed in `data/TransportProfileRegistry.kt`.
Chrome is the only enabled fixture today. Firefox or another profile should be
added only together with a validated shared-C++ TLS/H2 fixture, not merely a
different User-Agent string.

Android advertises one routing mode: the shared-native `packet_bulk_v1` VPN.
Every stored Tun2Socks, packet-beta, or SOCKS-only choice migrates to that mode.
The old SOCKS service is no longer an Android component, the VPN's unused
`connectLegacy()` path is removed, and startup never falls back to a parallel
Kotlin implementation of the dev6 wire protocol.

## Native build and packaging

Gradle runs `~/yume/tools/build_android_shared_abi.sh` before its ndk-build
tasks. The script:

1. refuses root;
2. verifies the signed pinned `origin/main` baseline and requires HEAD to be
   descended from it;
3. uses the pinned Android NDK and vcpkg manifest;
4. builds the shared ABI directly from the sibling source tree for
   `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` by default;
5. stages only `libyume.so` and the public `include/yume/yume.h` beneath
   `app/build/yume-shared-abi/`.

The script exports the selected pinned NDK as `ANDROID_NDK_HOME` and
`ANDROID_NDK_ROOT` for vcpkg's separate Android compiler-detection process;
the top-level CMake chainload option alone does not reach that subprocess.
Gradle clean tasks do not depend on this build, so `build.sh` compiles the
shared ABI once during assemble rather than once before and once after clean.
AGP's `externalNativeBuildClean*` tasks are disabled because ndk-build parses
missing prebuilt ABI inputs even while cleaning; the main Gradle `clean` task
deletes `app/build` and the remaining generated `app/.cxx` tree directly.
Native compilation defaults to four parallel jobs to keep developer machines
responsive; direct script/CI callers can override this with `--jobs`. Each ABI
also keeps its complete compiler output at
`app/build/yume-shared-abi/.build/<abi>/yume-abi-build.log`, and Gradle includes
the latest log tail in a failed task's diagnostic.

Native dependencies use static vcpkg Android libraries so the app ships one
YUME runtime plus Android's `libc++_shared.so`. Desktop builds retain the
`libyume.so.1` SONAME. Android uses the unversioned filename required by APK
native-library packaging while reporting `YUME_ABI_VERSION == 1` at runtime.
The client-only ABI substitutes an explicit unavailable result for the desktop
GitHub update checker, keeping Boost.Beast and unused outbound update traffic
out of the APK; Android presentation/update policy remains Kotlin-owned.
The APK uses the client-only ABI build profile: the public ABI export set stays
stable, server entrypoints fail closed, and no server runtime is linked. The
Android packet profile requests one persistent carrier tunnel; packet-bulk
multiplexes the complete IPv4 session over that carrier.

Set `VCPKG_ROOT` if vcpkg is not available at `~/vcpkg`. A single-ABI build can
use `-PyumeAbi=arm64-v8a`; release builds must exercise all four configured
ABIs before shipping.

For the signed release workflow, run `./build.sh` as the checkout owner (never
through `sudo`). It builds the `direct` flavor by default; use
`--distribution play` for the Play flavor and `--abi` to select split APKs.
The script recognizes flavor-qualified Gradle filenames and signs only the
requested ABI outputs.

## Fail-closed startup

Startup rejects the session unless all of these hold:

- native ABI is exactly 1 and native/source versions are exactly `0.2.0-dev6`;
- `YUME_FEATURE_PQ_MLKEM1024` and `YUME_FEATURE_PACKET_BULK` are present;
- the PQ backend is available;
- admission secret and inner PSK are each exactly 32 bytes represented as 64
  lowercase hex characters;
- the authenticated HTTP/2 carrier is enabled;
- the carrier host is resolved through the selected underlying Android
  `Network`, the native `server` field is numeric, and TLS verification retains
  the configured server name (or the original host when no override is set);
- TLS CA/name validation succeeds, plus the leaf pin when configured;
- the protected native carrier socket is accepted by `VpnService.protect()`;
- the server advertises `packet_bulk_v1` and packet OPEN succeeds;
- assigned IPv4/MTU/protocol values pass native and Android checks.

Packet v1 is IPv4-only. Android installs no IPv6 address, route, DNS server, or
`allowFamily(AF_INET6)` exemption, so `VpnService` blocks the unsupported
family instead of leaking it to the underlying network. The assigned IPv4
address is installed as `/32`, while
the Android VPN route captures IPv4 `0.0.0.0/0`. The shared packet channel
validates packet direction/address and rejects invalid sequences and sizes.

Shutdown order is packet close, reader interruption/join, packet destroy,
one client destroy, material-file deletion, and TUN close. Network-handle
changes cancel pending DNS, restart the complete native session, and rebuild
the VPN interface. Android 10+ uses cancellable `DnsResolver` on the exact
underlying network. Android 9 has no cancellable resolver API, so a cancelled
lookup is abandoned on a daemon worker that owns no service state; stop/revoke
does not wait for it and late results are discarded. VPN permission
revocation uses the same idempotent cleanup path.

The service enters the foreground synchronously before asynchronous credential
and native setup, declares Android Always-on support, and uses `START_STICKY`.
Only the selected profile id is retained for a platform restart; an auth-key
password is never persisted. Profiles backed by password-locked key material
therefore fail closed after process death and require the user to unlock them
again. Explicit disconnect, VPN revoke, and terminal startup failure clear the
restart profile.

Encrypted `.yss` profiles use the BaseFWX minimum of 12 password characters.
Current exports preserve the TLS CA, TLS/SNI name, one-tunnel Android setting,
operator-proof requirement, admission secret, and inner PSK. Imports of older
v1 files that contain only an operator CA also use that CA as their private TLS
issuer, matching the old test-kit convention without making the built-in CA
mandatory.

Session PEMs and 32-byte secrets are staged beneath a private `0700`
no-backup directory with `0600` files. Normal shutdown deletes them; the next
session also removes crash leftovers, restricted to direct UUID-named,
non-symlink directories and the four known filenames.

## Presentation and configuration surface

The app presents only behavior it executes. There is no routing choice, no
performance profile, and no SOCKS5 configuration: Settings states the fixed
posture (`packet_bulk_v1` IPv4, TLS 1.3 + HTTP/2 WebSocket carrier, composite
Ed25519 + ML-DSA-87 AUTH v2, ML-KEM-1024 + X25519 + PSK with AES-256-GCM, IPv6
blocked) and repeats that the Android device gates below are still open. No
visible or persisted string calls any part of the app "beta".

The server editor no longer offers an HTTPS-masking toggle. The carrier is
mandatory — `prepare_v2_security_config()` on the server and
`SharedYumeSessionConfig.create()` on the client both refuse `obfuscation=false`
— so it is stated as fixed posture and stored profiles are migrated to `true` on
read. The editor's real inputs are the admission secret, the inner PSK, the
optional TLS leaf pin, and the TLS profile; the first two are required 64-char
lowercase hex values and are validated on save and again before connect.

Auth-key text describes the dev6 identity as it is validated: one composite file
holding an Ed25519 PKCS#8 block followed by an ML-DSA-87 block, generated by
`yume --keys-gen` on the desktop. `YumeAuthKeyValidator` enforces exactly that
count, order, and pair of algorithm OIDs.

Diagnostics reports live session facts — route, carrier, authentication, inner
tunnel, ratchet, packet batch limit, assigned IPv4, MTU, DNS, IPv6 and DNS
policy — plus the open-qualification note. The former runtime block printed
tun2socks/SOCKS bridge tuning that reached nothing on the packet path, and the
"Throughput logging" and "Self-DPI warnings" switches only ever drove the
retired Kotlin wire; all three are gone. The status sheet reports Route rather
than "Local SOCKS5" and Ratchet rather than "Hop", and no longer shows a relay
canary row the packet path never fills.

The source no longer builds or credits the former hev-derived SOCKS-to-TUN
native stack. The current ARM64 `directAgent` APK proves it is no longer
packaged; Android's new ndk-build input contains only the shared `libyume.so`
prebuilt and the narrow `libyumeandroid.so` JNI adapter. The next ordinary
release APK must repeat that package gate. Retired Kotlin wire sources remain
separate cleanup debt, but they are not selectable or part of the supported
native routing path.

The maintained Russian, Japanese, and Ukrainian bundles cover every English
source string; `AppStringsTranslationTest` enforces that at build time.

## Validation already safe to run locally

- `bash -n ~/yume/tools/build_android_shared_abi.sh`
- JNI wrapper `clang++ -fsyntax-only` with pinned NDK 28.2 headers
- focused standalone JVM validation of `YumeAuthKeyValidatorTest`
- `git diff --check` in both repositories

A Gradle configuration-only check may need network access when the Foojay
toolchain plugin is absent from the local cache. Full APK/device validation is
still a separate gate even when Kotlin and the selected native ABI compile.

When the operator explicitly authorizes a bounded local physical-device run,
`tools/android-agent.sh` builds and controls the isolated `directAgent` APK.
See `ANDROID_AGENT_TESTING.md`. Its structured snapshot exposes the actual
`YumeVpnRuntime` state, packet totals/rates, connection milestones, process
resources, and event log; its network benchmark runs inside the Android VPN
path. The agent manifest is not merged into release/debug APKs, and one-time
VPN consent remains an Android-controlled user action.

## Required remote/device gates

### Current bounded physical-device evidence

On the attached Pixel 9 Pro (Android 17/API 37, arm64-v8a), the isolated
`directAgent` APK builds with two Gradle workers and a 4-GiB heap, installs, and
reports the loaded native boundary: ABI 1, feature flags 63, exact YUME
`0.2.0-dev6`, BaseFWX `3.8.0-dev1`, and an available ML-KEM backend. The APK
packages `libyume.so`, `libyumeandroid.so`, and `libc++_shared.so`, not the
removed route core. A full local self-test, a 120-second sustained workload,
three quick runs, repeated native create/stop/close, and bounded idle command
loops pass without continuing fd/thread growth. Ordinary debug/release merged
manifests contain no ADB-agent component.

The exact full run completed in 147.886 seconds. Its eight-thread diagnostic
rows measured 1,234.7 MiB/s packet-bulk encode, 924.8 MiB/s packet-bulk decode,
and 723.9 MiB/s during the 120-second mixed workload. Three 1-thread quick runs
measured a cold post-install 201.8/284.5 MiB/s and warm 411.6/420.1 and
404.2/411.7 MiB/s packet encode/decode; the three-run medians are 404.2/411.7.
These rows exercise the Kotlin `YumePacketBulk` diagnostic and BaseFWX Java
paths in the debuggable agent. They do **not** exercise the supported
TUN -> `SharedYumePacketEngine` -> native `libyume.so` network route and must
not be published as VPN throughput or compared directly with desktop C++
component rows.

For orientation only, an exact-current regular-core refresh on the i9-14900K
host measured 15,763.0/15,223.9 MiB/s median packet-bulk encode/decode in the
same 1-thread/32-MiB component geometry, and 239.4/252.1 Mbit/s median
upload/download through the production DATA/ratchet/H2/WebSocket/TLS path at
60 ms RTT. The first pair is different code on different hardware; the second
pair excludes the packet ABI, TUN, Android framework, JNI, and device network.
Neither is a valid Android/core speed ratio. The supported Android route remains
performance-unclassified until the connected in-app benchmark and matched
packet-ABI/TUN test can run.

This is deliberately not routed-VPN acceptance. The isolated vault has no
operator-approved profile, and no approved `.yss`/password or generated server
kit was present on the authorized hosts. Real connect, traffic, exit/DNS/IPv6,
timing/throughput, connected lifecycle/backpressure, and server/credential
negative gates remain open. The Android build also currently reports liboqs
0.15.0 from the pinned official vcpkg registry while YUME dependency metadata
declares 0.16.0 as the minimum; release provenance remains open until that is
resolved without an unreviewed dependency fork.

On the confirmed build host, copy both repositories into an isolated exact
snapshot without overwriting a live checkout, retain the YUME Git metadata
needed by the signed-baseline producer check, install the pinned NDK and a clean
vcpkg checkout, then build ARM64 native first. Verify build info, exports,
`readelf` dependencies, and packaged libraries before Kotlin/JNI compilation
and unit tests. Expand to the other ABIs only after ARM64 and device behavior
pass.

Representative Gradle commands after the ARM64 producer gate:

```bash
cd ~/AndroidStudioProjects/Yume
VCPKG_ROOT=~/vcpkg ./gradlew :app:compileDirectDebugKotlin :app:testDirectDebugUnitTest -PyumeAbi=arm64-v8a
VCPKG_ROOT=~/vcpkg ./gradlew :app:assembleDirectDebug -PyumeAbi=arm64-v8a
VCPKG_ROOT=~/vcpkg ./gradlew :app:connectedDirectDebugAndroidTest
```

For every supported ABI, confirm the APK contains exactly one `libyume.so`,
`libyumeandroid.so`, and `libc++_shared.so`, with no `libyumetransport.so`.
Run the feature/lifecycle instrumentation test on hardware or an emulator of
the matching ABI.

Against `yumed` from the same shared source revision:

1. verify exact `0.2.0-dev6`, the expected TLS fingerprint, ML-KEM-1024, and
   `packet_bulk_v1` in Android status;
2. pass IPv4 DNS, TCP, and UDP traffic through the Android VPN and confirm the
   assigned source address and MTU;
3. verify wrong version, capability, admission secret, inner PSK, TLS CA/name,
   pin, packet source, MTU, and IPv6 cases fail closed;
4. saturate both directions and exercise `WOULD_BLOCK`, timeout, and
   buffer-too-small without consuming the first queued packet;
5. run repeated connect/disconnect, airplane-mode, Wi-Fi/cellular handoff, and
   VPN-revoke cycles while checking threads and `/proc/self/fd` for growth;
6. run forward and reverse iperf tests using the same server, packet MTU,
   physical route, duration, and stream count as the desktop ABI/CLI baseline;
7. run the broader 100-client server scalability gate separately. It validates
   the shared server, not one Android device.

Record device model, Android version, ABI, APK hash, YUME commit, yumed commit,
test commands, pass/fail output, and forward/reverse throughput. Only then may
Android YUME 2.0 support be described as validated.
