# Android ADB agent harness

`directAgent` is an isolated, debug-signed APK for repeatable physical-device
testing. It installs as `com.fixcraft.yume.agent`, so it cannot overwrite the
normal `com.fixcraft.yume` app or reuse its vault. `libyume` and the JNI
adapter are release-optimized. Android disables R8 optimization for
debuggable APKs, so take final Kotlin and UI performance numbers from a
release APK.

The control surface is absent from release and ordinary debug manifests. In
the agent APK, only the shell or system `android.permission.DUMP` holder can
open the transparent command activity, which forwards bounded commands to a
non-exported foreground service. Results and secrets stay in the app-private
`no_backup/adb-agent` directory.

A kit's code is a secret. It never travels as an intent extra or a
command-line argument. The host writes the sealed kit and its code through
stdin into two mode-0600 `.partial` files named after one command ID and
renames them into place. The service opens and unlinks each file before it
reads it, so neither outlives the command, and wipes the bytes on every exit
path. The host and the APK both cap a kit at sealed kit 1's largest size and
a code at 128 bytes. Startup deletes the fixed-name inputs an agent build for
transport v2 used.

Each command has its own ID and result file, and concurrent invocations use
separate activity tasks, so one launch cannot swallow another. The service
serializes state-changing work and keeps itself alive until every queued
command has finished. At most 256 result files are kept.

## Server side

The server is YUME's own. On the server host:

```bash
yume-setup init --host vpn.example.org --port 443 --output ~/kit
yumed --config ~/kit/server/yumed.json
yume --seal-kit ~/kit/client --output device.kit   # prints the 25-character code
```

`docs/QUICKSTART.md` in YUME covers ports, the cover site and DNS. A kit
whose `endpoint.host` is a name is resolved on the phone's network. A test
server on a private address can be named by its address instead.

## Fast start on the attached phone

From this repository, on a host with the device attached:

```bash
./tools/android-agent.sh doctor
./tools/android-agent.sh install path/to/app-direct-agent.apk
./tools/android-agent.sh prepare
./tools/android-agent.sh import device.kit < code.txt
./tools/android-agent.sh kits
```

`prepare` is the one interactive step: Android requires the user to approve
each VPN package once. After that, connecting, stopping, snapshots, logs and
evidence collection need nobody. Set `ANDROID_SERIAL` when more than one
device is attached.

`build arm64-v8a` builds the APK with one ABI, two Gradle workers and a
4 GiB heap. It is still a full Android and NDK build, so it runs on the build
host, not the laptop. `YUME_UNVERIFIED_SOURCE=1` builds from a YUME copy
without Git metadata, for testing only.

## Connect, state and downloads

```bash
./tools/android-agent.sh connect            # the selected kit
./tools/android-agent.sh wait Connected 90
./tools/android-agent.sh status
./tools/android-agent.sh bench http://192.0.2.10:8080/large.bin 268435456 60000
./tools/android-agent.sh disconnect
./tools/android-agent.sh wait Idle 30

# One bounded connect, optional download, status and disconnect:
./tools/android-agent.sh smoke http://192.0.2.10:8080/large.bin

# A timestamped evidence directory:
./tools/android-agent.sh collect
```

`status` is JSON: the build identity, including what the loaded `libyume`
reports about itself, process memory, thread and descriptor counts, the VPN
phase, the endpoint's status snapshot (session, attempts, wait before the
next one, traffic, open connections, the server's identity fingerprint), the
traffic samples and the event log. The kept `last_operation` holds only a
command's metadata, so repeated `status` calls do not nest snapshots.

`bench` runs inside the app process and refuses to start unless the VPN is
connected. It accepts only HTTP and HTTPS, follows no redirect, applies byte
and time caps, and reports the bytes, wall time, time to first byte, Mbit/s
and the tunnel's counters. The app is inside its own VPN, so the download
crosses the device bridge like any other application's traffic.

## What remains a release gate

The harness makes device runs repeatable. It does not turn a green
notification into proof. Qualifying a release still needs a real external
`yumed`, public-address and DNS checks, IPv6 containment, saturation and
backpressure, network handoffs, revoke, process-death and reboot recovery,
wrong-kit refusals, repeated lifecycle and descriptor checks, and a signed
release APK from reviewed source.
