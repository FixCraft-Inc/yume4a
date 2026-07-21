# YUME 2.0-dev1 Android transport migration

## Status

The Android VPN entrypoint now consumes the shared C++ YUME client and public
packet ABI. It is based on signed `origin/main` commit
`491a5738d9db998e2155de05619b0f893b8b128f` from the sibling `~/yume`
checkout. `2.0-dev1` is a development protocol target, not a final release.

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

The old Kotlin 1.1 transport remains as unreachable rollback code until device
parity is proven. The main VPN entrypoint cannot select it, and SOCKS-only mode
is blocked instead of silently using the old wire.

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

- native ABI is exactly 1 and native/source versions are exactly `2.0-dev1`;
- `YUME_FEATURE_PQ_MLKEM1024` and `YUME_FEATURE_PACKET_BULK` are present;
- the PQ backend is available;
- admission secret and inner PSK are each exactly 32 bytes represented as 64
  lowercase hex characters;
- the authenticated HTTP/2 carrier is enabled;
- TLS CA/name validation succeeds, plus the leaf pin when configured;
- the protected native carrier socket is accepted by `VpnService.protect()`;
- the server advertises `packet_bulk_v1` and packet OPEN succeeds;
- assigned IPv4/MTU/protocol values pass native and Android checks.

IPv6 is not captured. The assigned IPv4 address is installed as `/32`, while
the Android VPN route captures IPv4 `0.0.0.0/0`. The shared packet channel
validates packet direction/address and rejects invalid sequences and sizes.

Shutdown order is packet close, reader interruption/join, packet destroy,
client stop/destroy, material-file deletion, and TUN close. Network-handle
changes restart the complete native session and VPN interface. VPN permission
revocation uses the same idempotent cleanup path.

Session PEMs and 32-byte secrets are staged beneath a private `0700`
no-backup directory with `0600` files. Normal shutdown deletes them; the next
session also removes crash leftovers, restricted to direct UUID-named,
non-symlink directories and the four known filenames.

## Validation already safe to run locally

- `bash -n ~/yume/tools/build_android_shared_abi.sh`
- JNI wrapper `clang++ -fsyntax-only` with pinned NDK 28.2 headers
- host client-only facade and shared `yume_abi` compilation
- `yume_abi_test` and `yume_abi_exports`
- protected-socket allow/deny regression test
- `git diff --check` in both repositories

A Gradle configuration-only check may need network access when the Foojay
toolchain plugin is absent from the local cache. Do not refresh dependencies or
run Android builds on the daily-driver laptop.

## Required remote/device gates

Ask which machine to use before running these. `remote-builder`
(`build-host.example`) is preferred, but is not assumed.

On the build machine, sync both repositories, install the pinned NDK and
a clean vcpkg checkout, then run:

```bash
cd ~/AndroidStudioProjects/Yume
VCPKG_ROOT=~/vcpkg ./gradlew :app:assembleDirectDebug
VCPKG_ROOT=~/vcpkg ./gradlew :app:connectedDirectDebugAndroidTest
```

For every supported ABI, confirm the APK contains exactly one `libyume.so`,
`libyumeandroid.so`, and `libc++_shared.so`, with no `libyumetransport.so`.
Run the feature/lifecycle instrumentation test on hardware or an emulator of
the matching ABI.

Against `yumed` from the same shared source revision:

1. verify exact `2.0-dev1`, the expected TLS fingerprint, ML-KEM-1024, and
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
