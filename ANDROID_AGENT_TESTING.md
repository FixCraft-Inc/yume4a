# Android ADB agent harness

`directAgent` is an isolated, debug-signed APK for repeatable physical-device
testing. It installs as `com.fixcraft.yume.agent`, so it cannot overwrite the
normal `com.fixcraft.yume` app or reuse its vault. The native shared YUME core
and JNI adapter are release-optimized; Android disables Java/Kotlin R8
optimization for debuggable APKs, so use release APKs for final publishable
Java/UI performance numbers.

The control surface is absent from release and ordinary debug manifests. In the
agent APK, only the shell/system `android.permission.DUMP` holder can open the
transparent command activity. That activity forwards bounded commands to a
non-exported foreground service. Results and secrets stay in the app-private
`no_backup/adb-agent` directory. Passwords and `.yss` inputs are one-shot files
and are deleted after consumption; passwords are never broadcast extras or CLI
arguments.

Each secret-bearing command owns a unique command ID. The host writes
only that command's bounded input through stdin to a mode-0600 `.partial` file,
then atomically renames it inside the app-private directory. The service opens
and unlinks the matching file before reading or checking profile/VPN
preconditions, then wipes its in-memory bytes on every exit path. Import
attempts to consume both matching files before propagating either input error,
and rolls back a newly saved profile, prior selection, and referenced material
if result publication fails. The host and APK independently cap `.yss` inputs
at 2 MiB and passwords at 4 KiB. Startup also deletes legacy fixed-name
inputs. Concurrent invocations use separate activity tasks, so a second shell
launch cannot be folded into and dropped by an existing command activity. A
failed command must leave neither the command-ID input nor a legacy input
behind.

## Fast start on the attached phone

Run from this Android repository:

```bash
./tools/android-agent.sh doctor
./tools/android-agent.sh build arm64-v8a
./tools/android-agent.sh install
./tools/android-agent.sh prepare
```

`prepare` is the only intentionally interactive step: Android requires the
user to approve each VPN app package once. After approval, connect/disconnect,
tests, snapshots, logs, and artifact collection are autonomous. Set
`ANDROID_SERIAL` if more than one device is attached.

The build command uses `directAgent`, one ABI, two Gradle workers, and a 4 GiB
heap cap. It remains a full Android/NDK build and therefore still requires
explicit operator authorization on the daily-driver laptop.

The agent package has a separate app vault. Import an existing encrypted YUME
share bundle once, with the password supplied through stdin:

```bash
./tools/android-agent.sh import /secure/path/device-profile.yss < /secure/path/yss-password
./tools/android-agent.sh profiles
```

The import result prints the new profile ID. No secrets appear in `profiles`.
For an auth key that remains password-encrypted after setup, connect with a
one-shot stdin password:

```bash
./tools/android-agent.sh connect PROFILE_ID --password-stdin < /secure/path/key-password
```

Most imported `.yss` backups contain the key and are stored under Android
Keystore protection, so ordinary connect needs no password argument.

## Smoke, state, and benchmarks

```bash
./tools/android-agent.sh connect PROFILE_ID
./tools/android-agent.sh wait Connected 90
./tools/android-agent.sh status
./tools/android-agent.sh self-test quick
./tools/android-agent.sh bench http://192.0.2.10:8080/large.bin 268435456 60000
./tools/android-agent.sh disconnect

# One bounded connect / routed check / optional download / disconnect cycle:
./tools/android-agent.sh smoke PROFILE_ID http://192.0.2.10:8080/large.bin

# Capture a timestamped evidence directory:
./tools/android-agent.sh collect
```

`status` is JSON and includes exact build identity, process memory/thread/fd
counts, VPN phase and negotiated facts, connection phase timings, packet totals,
current rates, 120 recent one-second samples, and the structured runtime log.
The persisted `lastOperation` field is deliberately compact: snapshot commands
return the full current snapshot to the caller but retain only command/result
metadata with `data.omitted_from_snapshot=true`. Never persist a full snapshot
inside another snapshot; doing so recursively grows JSON, Binder work, and app
memory under repeated `status` calls.

`bench` runs inside the Yume app process and refuses to start unless the VPN is
connected with verified routed traffic. It accepts only HTTP(S), follows no
redirects, applies byte/time caps, and reports HTTP bytes, wall time, TTFB,
Mbit/s, and tunnel RX/TX deltas. On Android 13+ the Yume app is inside its own
VPN data path, while native control sockets and server routes are explicitly
protected from recursion.

`self-test quick` first runs 100 native client create/stop/close iterations with
an fd-growth bound, then exercises local BaseFWX AES-GCM, packet-bulk
encode/decode, HKDF, memory, and stream paths. `self-test full` adds the adaptive
long workload, PQ, Argon2, disk, thermal, CPU, and memory sampling. The snapshot
records the loaded native ABI, feature flags, versions, and PQ backend. The local
self-test is not a network benchmark. In particular, its packet-bulk throughput
rows exercise the Kotlin `YumePacketBulk` diagnostic codec; they do not measure
the active `SharedYumePacketEngine`/`libyume.so` packet C ABI, TUN I/O, JNI, the
carrier, or the server. Use a connected in-app download plus a matched
packet-ABI/TUN transfer for Android performance decisions.

Commands can run concurrently: each response has a unique command ID and
private result file, while the in-app worker serializes state-changing work.
Active-start accounting keeps the service alive until every queued command has
completed, independent of coroutine scheduling order. Snapshot durability
precedes final result publication, and abandoned result files are capped at
256 so interrupted or timed-out host invocations cannot grow app-private
storage without bound.

## Starting a generated yumed kit

The harness can manage the `server/` directory generated by YUME's setup tool.
The directory must contain `start-cover` and `start-yumed`; `--packet` also runs
the generated packet route helper and therefore needs already-configured
non-interactive privilege or a prior manual route setup.

```bash
./tools/android-agent.sh server-start local /path/to/kit/server --packet
./tools/android-agent.sh server-start 192.0.2.10 /home/user/kit/server --packet
./tools/android-agent.sh server-status 192.0.2.10 /home/user/kit/server
./tools/android-agent.sh server-stop 192.0.2.10 /home/user/kit/server --packet
```

The remote helper uses passwordless SSH and writes only `.android-agent/` PID
and log files inside the exact supplied server directory. `start-yumed` must be
able to find a matching `yumed` binary (normally through `YUMED_BIN`,
`~/yume/build/bin/yumed`, or `PATH`). The APK profile, server kit, and yumed
binary must all represent the same dev6 credentials and source revision.
With `--packet`, start first validates the packet-up/down helpers, then marks
`packet.enabled` before packet-up. It starts the cover, requires packet-up to
succeed noninteractively, and starts yumed. A failed start boundedly stops both
processes and runs packet-down. Successful packet teardown clears
`packet.enabled`; any incomplete process or packet rollback creates the
separate `cleanup.required` marker. Start requires both managed processes and
matching packet state for idempotent success, and always rejects
`cleanup.required` until `server-stop` reconciles it. Stop marks cleanup before
changing state, verifies process exit, and clears both markers only after all
required teardown succeeds. Remote helpers use the fixed system PATH that
includes `/usr/sbin`, where `tc` resides on the benchmark host.

## What remains a release gate

The harness makes repeated device qualification practical; it does not turn a
green notification into proof. Final Android qualification still needs a real
external dev6 yumed, public-IP and DNS checks, IPv6 containment, saturation and
backpressure, network handoffs, revoke/process-death/reboot recovery, wrong
credential fail-closed cases, repeated lifecycle/fd checks, and a clean signed
release APK from reviewed source.
