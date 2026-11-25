# YUME for Android

YUME for Android runs a device VPN through
[YUME](https://github.com/FixCraft-Inc/yume)'s native YTP/1 transport. The app
owns Android's VPN interface, network selection, encrypted kit storage and
screens. The native library owns the transport, authentication and keys.

Import an operator's sealed client kit or enroll through a provider, then
select a service and connect. Source builds ship without a default provider
or browser registration.

The app shows connection status and payload traffic history, stores kits in
an encrypted vault, and offers per-kit tuning. The interface is available in
English, Russian, Japanese, Ukrainian and Simplified Chinese. Android blocks
IPv6 while the VPN is up when the tunnel has no IPv6 route. Circuit kits are
currently refused by the Android adapter.
See the [native implementation status](https://github.com/FixCraft-Inc/yume/blob/main/docs/IMPLEMENTATION_STATUS.md)
for YUME's supported surfaces and qualification limits.

## Build

Use JDK 21, Android SDK 36.1, NDK 28.2.13676358, Python 3 and the checked-in
Gradle wrapper. Set `VCPKG_ROOT` to a vcpkg checkout for the native build. See
[YUME's contributor guide](https://github.com/FixCraft-Inc/yume/blob/main/CONTRIBUTING.md)
for native prerequisites.

Gradle looks for YUME two levels above the app checkout. From a new workspace
directory, clone the projects into that layout:

```sh
git clone https://github.com/FixCraft-Inc/yume.git yume
git clone https://github.com/FixCraft-Inc/yume4a.git apps/Yume
git clone https://github.com/F1xGOD/basefwx.git yume/basefwx
basefwx_revision=$(python3 -c 'import json; print(json.load(open("yume/config/dependencies.json"))["dependencies"]["basefwx"]["revision"])')
git -C yume/basefwx checkout --detach "$basefwx_revision"
cd apps/Yume
```

The vault uses the Java runtime from the pinned BaseFWX checkout.

Before every build, Gradle runs YUME's
`tools/build_android_shared_abi.sh --verify-source-only`. It refuses a YUME
tree with uncommitted or untracked files, and runs `git verify-commit` on YUME's
`HEAD` and on the signed baseline commit the script pins. `git verify-commit`
checks the signatures against your GnuPG keyring, so import the YUME signing
key first. YUME ships the public key as
`third_party/prebuilt/yume-release-key.asc`, and the same key is published at
<https://github.com/F1xGOD.gpg>. From the app checkout, print the key:

```sh
gpg --show-keys ../../yume/third_party/prebuilt/yume-release-key.asc
```

It must be an ed25519 primary key with the fingerprint
`967278FF6FA436F504CBB0058A1588B5E2598DB1`
(`9672 78FF 6FA4 36F5 04CB  B005 8A15 88B5 E259 8DB1`, key ID
`8A1588B5E2598DB1`). If it matches, import it:

```sh
gpg --import ../../yume/third_party/prebuilt/yume-release-key.asc
```

For a local test build from a modified or unsigned YUME tree,
`-PyumeUnverifiedSource=true` skips the source check. Release builds refuse
that property.

The shared provider library requires an explicit version and source binding.
From this checkout, generate its manifest and use the returned `version` and
`source_sha256` values:

```sh
python3 tools/provider_source_manifest.py --root provider-library
./gradlew --no-daemon --max-workers=2 \
  -PyumeProviderVersion=REVIEWED_VERSION \
  -PyumeProviderSourceSha256=REVIEWED_SOURCE_SHA256 \
  -PyumeAbi=x86_64 \
  :app:testDirectDebugUnitTest :app:assembleDirectDebug :app:lintDirectDebug
```

The [provider library guide](provider-library/README.md) owns the binding contract.
The default build excludes Google ML Kit. Optional browser enrollment needs a
distribution's explicit client registration and callback, as described in
[provider enrollment](docs/PROVIDERS.md).

`build.sh --apk-dir DIR` signs unsigned release APKs built elsewhere. Keep
the signing key and the ignored `.release-signing.env` on the signing machine.
The [example](.release-signing.env.example) names the required fields.

## Documentation and license

| Document | Covers |
| --- | --- |
| [Services and manual kits](docs/CONSUMER_SERVICES.md) | Selection, connection lifetime and removal |
| [Provider enrollment](docs/PROVIDERS.md) | Enrollment protocol and provider authority |
| [Provider client](docs/PROVIDER_CLIENT.md) | Shared client, storage and trust boundaries |
| [Provider library](provider-library/README.md) | Consumer boundary and source binding of the shared module |
| [Reference provider](docs/REFERENCE_PROVIDER.md) | Host-only provider fixture for conformance tests |
| [ADB test harness](docs/ADB_TEST_HARNESS.md) | Scripted device tests with the `directAgent` build |
| [Dependency notices](THIRD_PARTY_LICENSES.md) | Packaged licenses and corresponding source |

FixCraft Inc.'s original application code is AGPL-3.0-or-later, as stated in
[COPYRIGHT](COPYRIGHT) and [LICENSE](LICENSE). Dependencies retain their own
licenses and notices.
