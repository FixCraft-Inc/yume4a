# Third-party licenses

YUME on Android embeds a native tun↔SOCKS5 routing core that was originally
derived from open-source projects by hev <r@hev.cc> and from the lwIP
networking stack. The relevant in-tree copies preserve their original
copyright headers and LICENSE files; this document summarises them in one
place so a packager or auditor doesn't have to walk the tree.

YUME's own code (everything outside the directories listed below, plus
the substantial modifications marked *"Modifications copyright (c) 2026
FixCraft Inc."* in the file headers) is © FixCraft Inc., released under
the project's primary license.

---

## hev-socks5-tunnel (basis of the native routing core)

- **Location in tree:** `app/src/main/jni/yume-route-core/` (renamed from
  the upstream `hev-socks5-tunnel/`)
- **Upstream:** https://github.com/heiher/hev-socks5-tunnel
- **License:** MIT — full text in
  `app/src/main/jni/yume-route-core/LICENSE`
- **Status:** originally derived; significantly modified for Android
  integration (mapped DNS, native transport callbacks, Android logging,
  direct-poll TCP fast path, lifecycle/JNI bindings). Of the 52 source
  files carrying the original `hev <r@hev.cc>` author tag, 33 are
  byte-identical with upstream after symbol renames and 19 contain
  substantive YUME modifications — those 19 carry an added
  *"Modifications copyright (c) 2026 FixCraft Inc."* line in their
  header. Ongoing development is independent and does not track
  upstream.

## hev-socks5-core (used inside yume-route-core)

- **Location in tree:** `app/src/main/jni/yume-route-core/src/core/`
- **Upstream:** https://github.com/heiher/hev-socks5-core
- **License:** MIT — full text in
  `app/src/main/jni/yume-route-core/src/core/LICENSE`
- **Status:** unmodified except symbol renames.

## hev-task-system (used inside yume-route-core)

- **Location in tree:**
  `app/src/main/jni/yume-route-core/third-part/yume-task-system/`
  (renamed from `hev-task-system/`)
- **Upstream:** https://github.com/heiher/hev-task-system
- **License:** MIT — full text in
  `app/src/main/jni/yume-route-core/third-part/yume-task-system/LICENSE`
- **Status:** unmodified except symbol renames.

## lwIP

- **Location in tree:** `app/src/main/jni/yume-route-core/third-part/lwip/`
- **Upstream:** https://savannah.nongnu.org/projects/lwip/
- **License:** BSD-3-Clause — full text in
  `app/src/main/jni/yume-route-core/third-part/lwip/LICENSE`
- **Copyright:** © 2001, 2002 Swedish Institute of Computer Science.
- **Status:** unmodified port.

## libyaml (used by yume-route-core for config files)

- **Location in tree:**
  `app/src/main/jni/yume-route-core/third-part/yaml/`
- **Upstream:** https://pyyaml.org/wiki/LibYAML
- **License:** MIT — full text in
  `app/src/main/jni/yume-route-core/third-part/yaml/License`
- **Status:** unmodified.

---

## BaseFWX (runtime dependency, not in this tree)

YUME on Android depends at runtime on BaseFWX for inner crypto. BaseFWX
is a separate FixCraft project shipped as its own artifact; its license
applies independently and is not reproduced here.

---

If you redistribute this app, the four upstream LICENSE files
(MIT × 3, BSD-3-Clause × 1) must travel with the source or with the
binary. Keeping them at their in-tree locations satisfies that
requirement.
