# Third-party licenses

YUME's dependency credits are shown in the app's Open-source components
screen. The former vendored hev-socks5-tunnel, hev-socks5-core,
hev-task-system, lwIP, and libyaml route stack was removed together with the
unused SOCKS-to-TUN native module; those projects are no longer source or binary
dependencies of the Android app.

## BaseFWX (runtime dependency, not in this tree)

YUME on Android depends at runtime on BaseFWX for inner crypto. BaseFWX
is a separate FixCraft project shipped as its own artifact; its license
applies independently and is not reproduced here.

---

The shared YUME producer, BaseFWX, liboqs, Conscrypt, Jetpack Compose, and
kotlinx.coroutines remain dependencies and must retain their applicable notices
in redistributed source and artifacts.
