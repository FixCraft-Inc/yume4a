# YUME for Android

YUME for Android runs a device VPN through
[YUME](https://github.com/FixCraft-Inc/yume)'s native YTP/1 transport. The app
owns Android's VPN interface, network selection, encrypted kit storage and
screens. The native library owns the transport, authentication and keys.

Import an operator's sealed client kit, or enroll through an optional provider,
then select a service and connect. Provider sign-in is optional. Source builds
contain no default provider or browser registration. Manual kits remain
available alongside provider services.

The app supports connection status, payload traffic history, per-kit tuning,
an encrypted vault, and English, Russian, Japanese, Ukrainian and Simplified
Chinese UI text. Android blocks IPv6 while the VPN is up when the tunnel has
no IPv6 route. Circuit kits are currently refused by the Android adapter.
See the [native implementation status](https://github.com/FixCraft-Inc/yume/blob/main/docs/IMPLEMENTATION_STATUS.md)
for YUME's supported surfaces and qualification limits.

## Build

Use JDK 21, Android SDK 36.1, NDK 28.2.13676358, the checked-in Gradle wrapper,
and the native prerequisites in
[YUME's contributor guide](https://github.com/FixCraft-Inc/yume/blob/main/CONTRIBUTING.md).
The expected checkout layout is:

```text
workspace/
  yume/
  apps/
    Yume/
```

The native checkout needs its pinned BaseFWX dependency for the vault's Java
runtime. Native source verification requires a signed, clean YUME commit and
the signer's public key in the build machine's GnuPG keyring.

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

Those two values are build inputs, not credentials. The
[provider library guide](provider-library/README.md) owns the binding contract.
The default build excludes Google ML Kit. Optional browser enrollment needs a
distribution's explicit client registration and callback, as described in
[provider enrollment](docs/PROVIDERS.md).

`build.sh --apk-dir DIR` signs unsigned release APKs built elsewhere. Keep
the signing key and the ignored `.release-signing.env` on the signing machine.
The [example](.release-signing.env.example) names the required fields.
Release builds reject unverified native source.

## Documentation and license

| Document | Covers |
| --- | --- |
| [Services and manual kits](docs/CONSUMER_SERVICES.md) | Selection, connection lifetime and removal |
| [Provider enrollment](docs/PROVIDERS.md) | Enrollment protocol and provider authority |
| [Provider client](docs/PROVIDER_CLIENT.md) | Shared client, storage and trust boundaries |
| [Dependency notices](THIRD_PARTY_LICENSES.md) | Packaged licenses and corresponding source |

FixCraft Inc.'s original application code is AGPL-3.0-or-later, as stated in
[COPYRIGHT](COPYRIGHT) and [LICENSE](LICENSE). Dependencies retain their own
licenses and notices.
