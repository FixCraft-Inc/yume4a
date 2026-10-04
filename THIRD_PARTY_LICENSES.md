# Third-party licenses

The application's Open-source components screen lists what it is built on.

## In libyume

`libyume.so` is built from the YUME checkout (AGPL-3.0-or-later) and links
statically:

- OpenSSL 3.5 with YUME's patch series (Apache-2.0, the YUME overlay under
  YUME's licence)
- Boost Asio, Beast and System (BSL-1.0)
- nghttp2 (MIT)
- zlib (Zlib), Zstandard (BSD-3-Clause) and Brotli (MIT)
- JSON for Modern C++ (MIT)

YUME's `docs/release/SBOM.spdx.json` lists their exact versions.

## In the application

- BaseFWX (GPL-3.0), whose Java sources are copied from YUME's BaseFWX
  checkout to wrap stored data under the optional app-data password
- Bouncy Castle (MIT)
- Jetpack Compose, Material 3 and AndroidX (Apache-2.0)
- kotlinx.coroutines (Apache-2.0)
- ML Kit Translation (ML Kit Terms of Service), only in builds made with
  `-PyumeMlKitTranslation=true`

The vendored hev-socks5-tunnel, lwIP and libyaml route stack, Conscrypt and
the transport-v2 Kotlin code were removed and are no longer source or binary
dependencies. Every remaining dependency keeps its notices in redistributed
source and artifacts.
