# Dependency licenses and notices

The application's original FixCraft Inc. code is licensed under
AGPL-3.0-or-later by [COPYRIGHT](COPYRIGHT), including earlier revisions.
[LICENSE](LICENSE) contains the full text. Both files are packaged in
`assets/notices/` alongside the dependency texts. Third-party code keeps its
respective grants, including in historical revisions.

Every APK carries complete dependency license texts and notices in
`assets/notices/`. `manifest.json` identifies its resolved runtime artifacts,
their SHA-256 hashes, the selected notice files and their upstream origins.
The Open-source components screen supplies a summary of component roles and
SPDX labels. The full texts are in the archive.

`notices/catalog.json` is the packaging source. It references existing YUME
and BaseFWX license files and the upstream copies under `notices/texts/`.
Gradle selects the notices from the actual runtime classpath, verifies the
reviewed artifact and text hashes, and generates the assets for every direct
and play build type. Unknown or changed dependencies require another notice
review. Excluded duplicate Java resources do not remove these assets.

The shared Android provider source project is covered by this repository's
`COPYRIGHT` and `LICENSE` (AGPL-3.0-or-later). Its independent version and exact
source binding are packaged in `assets/provider-library/provenance.json` and
the notice manifest's `providerIdentity`. Each variant records the resolved
AAR hash. It contains no application UI, VPN service or native library.

## Native transport

YUME's `libyume.so` implements the transport. YUME and its OpenSSL patch series
are licensed under AGPL-3.0-or-later, copyright 2026 FixCraft Inc. The APK
carries YUME's full AGPL text. This describes the native dependency's grant.
The Android application's own grant is in `COPYRIGHT` and `LICENSE`.

The library statically includes these dependencies:

| Component | License | Version source |
| --- | --- | --- |
| OpenSSL 3.5.7 with YUME's patch series | Apache-2.0 and AGPL-3.0-or-later for the patch series | YUME `config/dependencies.json`, `scripts/ensure-openssl.sh` and `patches/openssl/series` |
| Boost Asio, Beast, System and their header dependencies | BSL-1.0 | Android vcpkg installed metadata, currently 1.90.0 |
| nghttp2 | MIT | Android vcpkg installed metadata, currently 1.68.0 |
| zlib | Zlib | Android vcpkg installed metadata, currently 1.3.1 |
| Zstandard | BSD-3-Clause option of its dual license, with incorporated source notices | Android vcpkg installed metadata, currently 1.5.7 |
| Brotli | MIT | Android vcpkg installed metadata, currently 1.2.0 |
| nlohmann/json | MIT, with upstream incorporated-code notices | YUME `third_party/nlohmann_json/PROVENANCE.md`, currently 3.11.3 |

The separate `libc++_shared.so` uses the pinned Android NDK. Its complete LLVM
notice, including LLVM exceptions and incorporated third-party notices, is
bundled. AndroidX supplies `libandroidx.graphics.path.so` under Apache-2.0.
Neither is part of BaseFWX. The source dependency SBOM in YUME describes source
inputs and pins. It is not an inventory of the libraries in a particular APK.

The nlohmann/json bundle retains the UTF-8 decoder, Grisu2 and Hedley
attributions and the full Hedley CC0 text. The Zstandard bundle retains its
xxHash and divsufsort source notices. These accompany the parent libraries'
license texts.

## Application dependencies

BaseFWX Java wraps stored app data under the optional app-data password.
Its copied runtime and plugin API files use LGPL-3.0-or-later, copyright
2020-2026 FixCraft Inc. Both the LGPL supplement and the GPL v3 text it
incorporates are included. The source pin is YUME's
`config/dependencies.json`. No BaseFWX native library or CLI is packaged, and
BaseFWX does not implement the transport.

AppAuth for Android 0.11.1 constructs public-client OAuth requests and PKCE
S256 and selects the external browser. It uses Apache-2.0 and adds AndroidX
Browser 1.3.0, also Apache-2.0. The catalog packages AppAuth's complete upstream
license. The provider client does not serialize AppAuth authorization state.

Bouncy Castle 1.78.1 provides BaseFWX's Java primitives under its MIT license.
AndroidX, Compose, Material, Kotlin, coroutines, serialization and the resolved
annotation/support libraries use Apache-2.0. Their full applicable texts and
upstream notices are selected by the catalog, including Kotlin's NOTICE.
Debug tooling appears only in debug runtime inventories.

`-PyumeMlKitTranslation=true` adds ML Kit Translation and its Google, Firebase,
OkHttp, Okio and injection dependencies. Only those builds carry the ML Kit,
Google APIs and Android SDK terms and all upstream `third_party_licenses`
payloads from their resolved AARs. Each payload keeps its own namespaced
JSON/text pair so its license offsets remain valid. Default builds include
none of those optional notices or dependencies. These terms do not grant a
license to the Android application's own code; its grant is in `COPYRIGHT`.

## Corresponding source and replacement

Upstream source is at <https://github.com/FixCraft-Inc/yume>,
<https://github.com/FixCraft-Inc/yume4a> and
<https://github.com/F1xGOD/basefwx>. YUME's native build scripts, dependency pins
and OpenSSL patch series, and this application's Gradle files and explicit
BaseFWX Java include list, describe the build inputs.

A distributor must supply the corresponding source and build/install material
required by the applicable licenses for the exact binary, including any
modifications. An upstream URL or an SPDX label alone does not supply that
material. BaseFWX Java is merged and optimized into DEX, so replacing its files
requires rebuilding the application with the replacement library. Preserve the
source, build inputs and any additional replacement/relink material required by
the LGPL. Notice packaging alone does not establish distribution readiness.
Unverified dirty-native APKs are test builds.

## Archive regression

On the build host, after building a variant:

```sh
python3 tools/check_apk_notices.py \
  --expected-dir app/build/generated/dependency-notices/directRelease \
  app/build/outputs/apk/direct/release/app-direct-arm64-v8a-release-unsigned.apk
python3 tools/test_apk_notices.py
```

The check compares every generated notice byte with the APK and rejects missing,
truncated, duplicate or unexpected notice entries. Run it for each ABI split and
selected variant. Release manifest and DEX checks must also establish that agent
components are absent. Release signing and provision of corresponding source
remain separate distribution steps.
