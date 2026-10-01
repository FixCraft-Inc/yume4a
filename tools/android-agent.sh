#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="${YUME_ANDROID_AGENT_PACKAGE:-com.fixcraft.yume.agent}"
COMMAND_ACTIVITY="$PACKAGE/com.fixcraft.yume.agent.AgentCommandActivity"
CONSENT_ACTIVITY="$PACKAGE/com.fixcraft.yume.agent.AgentVpnConsentActivity"
PRIVATE_DIR="no_backup/adb-agent"
SNAPSHOT_FILE="$PRIVATE_DIR/snapshot.json"
CONSENT_FILE="$PRIVATE_DIR/consent.json"
DEFAULT_ABI="arm64-v8a"
MAX_BUNDLE_BYTES=$((2 * 1024 * 1024))
MAX_PASSWORD_BYTES=$((4 * 1024))

usage() {
  cat <<'EOF'
Usage: tools/android-agent.sh COMMAND [ARGS]

Build/device:
  doctor                         Verify one authorized ADB device and agent APK state
  build [ABI]                    Build optimized directAgent for one ABI (default arm64-v8a)
  install [APK]                  Install the newest agent APK, or the explicit APK
  launch                         Open the isolated agent app
  prepare                        Open Android's one-time VPN consent dialog and wait

Configuration/control:
  import BUNDLE.yss              Import a .yss bundle; reads its password from stdin
  profiles                       List non-secret profile metadata
  connect PROFILE_ID [--password-stdin]
  disconnect
  status                         Print the current machine-readable runtime snapshot
  wait PHASE [TIMEOUT_SECONDS]   Wait for Idle/Connecting/Connected/Error/etc.

Tests/telemetry:
  self-test [quick|full]         Run the existing on-device crypto/packet benchmark
  bench URL [MAX_BYTES] [TIMEOUT_MS]
                                 Download through the VPN and report timing/throughput
  smoke PROFILE_ID [URL]         Connect, verify routed state, optionally bench, disconnect
  logs                           Print Yume logcat plus the structured event log
  collect [OUTPUT_DIR]           Capture state, logs, package/device/memory evidence

Generated server kit helpers:
  server-start local|HOST SERVER_DIR [--packet]
  server-status local|HOST SERVER_DIR
  server-stop local|HOST SERVER_DIR [--packet]

Environment:
  ANDROID_SERIAL                 Select a device when more than one is attached
  YUME_ANDROID_AGENT_PACKAGE     Override the default com.fixcraft.yume.agent

Secrets are never accepted as command-line arguments. `import` and optional encrypted
auth-key passwords use stdin and one-shot app-private files deleted by the APK.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

select_device() {
  command -v adb >/dev/null 2>&1 || die "adb is not installed"
  [[ "$PACKAGE" =~ ^[A-Za-z][A-Za-z0-9._]*$ ]] || die "invalid agent package name"
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    ADB=(adb -s "$ANDROID_SERIAL")
  else
    mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ "${#devices[@]}" -eq 1 ]] || die "expected one authorized ADB device; set ANDROID_SERIAL"
    ADB=(adb -s "${devices[0]}")
  fi
}

run_as() {
  "${ADB[@]}" shell run-as "$PACKAGE" "$@"
}

validate_private_path() {
  [[ "$1" =~ ^no_backup/adb-agent/[A-Za-z0-9._-]+$ ]] || die "invalid agent private path"
}

read_private() {
  local target="$1"
  validate_private_path "$target"
  "${ADB[@]}" exec-out "run-as $PACKAGE sh -c 'test -f $target && cat $target'" 2>/dev/null
}

clear_private() {
  local target="$1"
  validate_private_path "$target"
  "${ADB[@]}" shell "run-as $PACKAGE rm -f -- $target" >/dev/null
}

write_private_stdin() {
  local target="$1"
  local temporary="$target.partial"
  validate_private_path "$target"
  validate_private_path "$temporary"
  "${ADB[@]}" shell "run-as $PACKAGE mkdir -p -- $PRIVATE_DIR" </dev/null >/dev/null
  if ! "${ADB[@]}" exec-in \
      "run-as $PACKAGE sh -c 'umask 077; cat > $temporary && mv -f -- $temporary $target'"; then
    clear_private "$temporary" || true
    return 1
  fi
}

new_command_id() {
  printf '%s-%s-%s\n' "$(date +%s%N)" "$$" "$RANDOM"
}

send_command_with_id() {
  local command_id="$1"
  local action="$2"
  local timeout_seconds="$3"
  shift 3
  local result_file="$PRIVATE_DIR/result-$command_id.json"
  # The shell launches exported activities in a task. Without MULTIPLE_TASK, a
  # concurrent invocation can merely bring the first transparent dispatcher to
  # the front (am result code 3), dropping the second command before the service
  # sees its command ID.
  "${ADB[@]}" shell am start -W --activity-multiple-task \
    -a "com.fixcraft.yume.agent.$action" \
    -n "$COMMAND_ACTIVITY" \
    --es command_id "$command_id" \
    "$@" >/dev/null

  local deadline=$((SECONDS + timeout_seconds))
  local result=""
  while (( SECONDS < deadline )); do
    result="$(read_private "$result_file" || true)"
    if [[ -n "$result" ]] && [[ "$(jq -r '.command_id // empty' <<<"$result" 2>/dev/null)" == "$command_id" ]]; then
      clear_private "$result_file" || true
      printf '%s\n' "$result"
      return 0
    fi
    sleep 0.2
  done
  clear_private "$result_file" || true
  printf 'error: timed out waiting for %s command %s\n' \
    "$action" "$command_id" >&2
  return 1
}

send_command() {
  local command_id
  command_id="$(new_command_id)"
  send_command_with_id "$command_id" "$@"
}

require_ok() {
  local result="$1"
  if [[ "$(jq -r '.ok' <<<"$result")" != "true" ]]; then
    jq . <<<"$result" >&2
    return 1
  fi
}

doctor() {
  local serial model release sdk abi package_state
  serial="$("${ADB[@]}" get-serialno | tr -d '\r')"
  model="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
  release="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
  sdk="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  abi="$("${ADB[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  if "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
    package_state="installed"
  else
    package_state="not installed"
  fi
  jq -n \
    --arg serial "$serial" \
    --arg model "$model" \
    --arg android "$release" \
    --arg sdk "$sdk" \
    --arg abi "$abi" \
    --arg package "$PACKAGE" \
    --arg package_state "$package_state" \
    '{serial:$serial,model:$model,android:$android,sdk:($sdk|tonumber),abis:$abi,package:$package,package_state:$package_state}'
}

build_agent() {
  local abi="${1:-$DEFAULT_ABI}"
  cd "$ROOT_DIR"
  GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx4g}" \
    ./gradlew --no-daemon --max-workers=2 --console=plain \
      :app:assembleDirectAgent "-PyumeAbi=$abi"
  newest_agent_apk
}

newest_agent_apk() {
  local apk
  apk="$(find "$ROOT_DIR/app/build/outputs/apk/direct/agent" -type f -name '*.apk' \
    -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -n 1 | cut -d' ' -f2-)"
  [[ -n "$apk" && -f "$apk" ]] || die "no directAgent APK found; run build first"
  printf '%s\n' "$apk"
}

install_agent() {
  local apk="${1:-}"
  [[ -n "$apk" ]] || apk="$(newest_agent_apk)"
  [[ -f "$apk" ]] || die "APK not found: $apk"
  "${ADB[@]}" install -r -t "$apk"
}

prepare_vpn() {
  clear_private "$CONSENT_FILE"
  "${ADB[@]}" shell am start -W -n "$CONSENT_ACTIVITY" >/dev/null
  printf 'Approve the Android VPN dialog if it appears (one-time setup).\n' >&2
  local deadline=$((SECONDS + 90))
  local value=""
  while (( SECONDS < deadline )); do
    value="$(read_private "$CONSENT_FILE" || true)"
    if [[ -n "$value" ]] && [[ "$(jq -r '.granted' <<<"$value" 2>/dev/null)" == "true" ]]; then
      jq . <<<"$value"
      return 0
    fi
    sleep 0.5
  done
  [[ -n "$value" ]] && jq . <<<"$value" >&2
  die "VPN consent was not granted within 90 seconds"
}

import_bundle() {
  local bundle="${1:-}"
  [[ -f "$bundle" ]] || die "bundle not found: $bundle"
  local bundle_bytes command_id bundle_file password_file
  bundle_bytes="$(stat -c '%s' -- "$bundle")" || die "could not inspect bundle: $bundle"
  [[ "$bundle_bytes" =~ ^[0-9]+$ ]] || die "invalid bundle size: $bundle"
  (( bundle_bytes >= 1 && bundle_bytes <= MAX_BUNDLE_BYTES )) || \
    die "bundle must be between 1 and $MAX_BUNDLE_BYTES bytes"
  command_id="$(new_command_id)"
  bundle_file="$PRIVATE_DIR/import-$command_id.yss"
  password_file="$PRIVATE_DIR/import-password-$command_id"
  (
    trap 'clear_private "$bundle_file" || true; clear_private "$bundle_file.partial" || true; clear_private "$password_file" || true; clear_private "$password_file.partial" || true' EXIT
    local password password_bytes result
    IFS= read -r -s password || true
    [[ -n "$password" ]] || die "read the .yss password from stdin"
    password_bytes="$(LC_ALL=C printf '%s' "$password" | wc -c)"
    (( password_bytes <= MAX_PASSWORD_BYTES )) || \
      die "password exceeds $MAX_PASSWORD_BYTES bytes"
    write_private_stdin "$bundle_file" < "$bundle"
    printf '%s' "$password" | write_private_stdin "$password_file"
    unset password
    result="$(send_command_with_id "$command_id" IMPORT_BUNDLE 180)"
    require_ok "$result"
    jq . <<<"$result"
  )
}

profiles() {
  local result
  result="$(send_command PROFILES 30)"
  require_ok "$result"
  jq '.data' <<<"$result"
}

connect_profile() {
  local profile_id="${1:-}"
  [[ -n "$profile_id" ]] || die "connect requires PROFILE_ID"
  [[ "${2:-}" == "" || "${2:-}" == "--password-stdin" ]] || \
    die "unknown connect option: ${2:-}"
  [[ -z "${3:-}" ]] || die "connect accepts only one option"
  local command_id password_file
  command_id="$(new_command_id)"
  password_file="$PRIVATE_DIR/auth-password-$command_id"
  (
    trap 'clear_private "$password_file" || true; clear_private "$password_file.partial" || true' EXIT
    local password password_bytes result
    if [[ "${2:-}" == "--password-stdin" ]]; then
      IFS= read -r -s password || true
      [[ -n "$password" ]] || die "read the auth-key password from stdin"
      password_bytes="$(LC_ALL=C printf '%s' "$password" | wc -c)"
      (( password_bytes <= MAX_PASSWORD_BYTES )) || \
        die "password exceeds $MAX_PASSWORD_BYTES bytes"
      printf '%s' "$password" | write_private_stdin "$password_file"
      unset password
    fi
    result="$(send_command_with_id "$command_id" CONNECT 30 --es profile_id "$profile_id")"
    require_ok "$result"
    jq . <<<"$result"
  )
}

disconnect_vpn() {
  local result
  result="$(send_command DISCONNECT 30)"
  require_ok "$result"
  jq . <<<"$result"
}

status() {
  local result
  result="$(send_command SNAPSHOT 30)"
  require_ok "$result"
  jq '.data' <<<"$result"
}

wait_phase() {
  local wanted="${1:-}"
  local timeout_seconds="${2:-60}"
  [[ -n "$wanted" ]] || die "wait requires a VPN phase"
  local deadline=$((SECONDS + timeout_seconds))
  local snapshot="" phase=""
  while (( SECONDS < deadline )); do
    snapshot="$(read_private "$SNAPSHOT_FILE" || true)"
    phase="$(jq -r '.vpn.phase // empty' <<<"${snapshot:-{}}" 2>/dev/null || true)"
    if [[ "${phase,,}" == "${wanted,,}" ]]; then
      jq . <<<"$snapshot"
      return 0
    fi
    if [[ "$phase" == "Error" && "${wanted,,}" != "error" ]]; then
      jq . <<<"$snapshot" >&2
      return 1
    fi
    sleep 0.5
  done
  [[ -n "$snapshot" ]] && jq . <<<"$snapshot" >&2
  die "timed out waiting for VPN phase $wanted (last: ${phase:-unknown})"
}

self_test() {
  local mode="${1:-quick}" timeout=240
  [[ "$mode" == "quick" || "$mode" == "full" ]] || die "self-test mode must be quick or full"
  [[ "$mode" == "full" ]] && timeout=1200
  local result
  result="$(send_command SELF_TEST "$timeout" --es mode "$mode")"
  require_ok "$result"
  jq . <<<"$result"
  read_private "$PRIVATE_DIR/self-test.txt"
}

network_bench() {
  local url="${1:-}"
  local max_bytes="${2:-67108864}"
  local timeout_ms="${3:-30000}"
  [[ -n "$url" ]] || die "bench requires URL"
  local wait_seconds=$((timeout_ms / 1000 + 45)) result
  result="$(send_command NETWORK_BENCH "$wait_seconds" --es url "$url" --el max_bytes "$max_bytes" --el timeout_ms "$timeout_ms")"
  require_ok "$result"
  jq . <<<"$result"
}

smoke() {
  local profile_id="${1:-}"
  local url="${2:-}"
  [[ -n "$profile_id" ]] || die "smoke requires PROFILE_ID"
  local connected=0
  cleanup_smoke() {
    if (( connected )); then
      disconnect_vpn >/dev/null 2>&1 || true
    fi
  }
  trap cleanup_smoke EXIT INT TERM
  connect_profile "$profile_id"
  connected=1
  wait_phase Connected 90 >/dev/null
  if [[ -n "$url" ]]; then
    network_bench "$url"
  fi
  status
  disconnect_vpn
  connected=0
  wait_phase Idle 30 >/dev/null
  trap - EXIT INT TERM
}

logs() {
  local pid
  pid="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r' || true)"
  if [[ -n "$pid" ]]; then
    "${ADB[@]}" logcat -d -v threadtime --pid="$pid"
  else
    "${ADB[@]}" logcat -d -v threadtime -s YumeVpn YumeAgent
  fi
  printf '\nStructured VPN events:\n'
  read_private "$SNAPSHOT_FILE" | jq '.vpn.events'
}

collect() {
  local output="${1:-$ROOT_DIR/artifacts/android-agent/$(date -u +%Y%m%dT%H%M%SZ)}"
  mkdir -p "$output"
  status > "$output/snapshot.json"
  profiles > "$output/profiles.json"
  doctor > "$output/device.json"
  "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$output/meminfo.txt"
  "${ADB[@]}" shell dumpsys package "$PACKAGE" > "$output/package.txt"
  logs > "$output/logs.txt"
  read_private "$PRIVATE_DIR/self-test.txt" > "$output/self-test.txt" 2>/dev/null || true
  printf '%s\n' "$output"
}

server_exec() {
  local host="$1"
  shift
  if [[ "$host" == "local" ]]; then
    bash -s -- "$@"
  else
    ssh -o BatchMode=yes "$host" bash -s -- "$@"
  fi
}

server_start() {
  local host="${1:-}" server_dir="${2:-}" packet="${3:-}"
  [[ -n "$host" && -n "$server_dir" ]] || die "server-start requires local|HOST SERVER_DIR"
  [[ "$packet" == "" || "$packet" == "--packet" ]] || die "unknown server-start option: $packet"
  server_exec "$host" "$server_dir" "$packet" <<'REMOTE'
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
server_dir="$1"
packet="$2"
cd -- "$server_dir"
[[ -x ./start-cover && -x ./start-yumed ]] || { echo "server kit scripts are missing" >&2; exit 1; }
mkdir -p .android-agent
if [[ -f .android-agent/cleanup.required ]]; then
  echo "managed server cleanup is required; run server-stop before restart" >&2
  exit 1
fi
if [[ "$packet" == "--packet" ]]; then
  [[ -x ./packet-up && -x ./packet-down ]] || {
    echo "packet-enabled server kit requires executable packet-up and packet-down" >&2
    exit 1
  }
elif [[ -f .android-agent/packet.enabled ]]; then
  echo "managed packet routing is still enabled; stop it before a non-packet start" >&2
  exit 1
fi
existing_yumed_pid=""
existing_cover_pid=""
for name in yumed cover; do
  pid_file=".android-agent/$name.pid"
  [[ -f "$pid_file" ]] || continue
  existing_pid="$(cat "$pid_file")"
  [[ "$existing_pid" =~ ^[0-9]+$ ]] || {
    echo "invalid managed $name PID file; run server-stop before restart" >&2
    exit 1
  }
  kill -0 "$existing_pid" 2>/dev/null || {
    echo "stale managed $name PID $existing_pid; run server-stop before restart" >&2
    exit 1
  }
  existing_command="$(tr '\0' ' ' < "/proc/$existing_pid/cmdline" 2>/dev/null || true)"
  case "$name" in
    yumed) [[ "$existing_command" == *yumed* ]] ;;
    cover) [[ "$existing_command" == *node* || "$existing_command" == *backend.mjs* ]] ;;
  esac || {
    echo "refusing stale $name PID $existing_pid: $existing_command" >&2
    exit 1
  }
  printf -v "existing_${name}_pid" '%s' "$existing_pid"
done
if [[ -n "$existing_yumed_pid" || -n "$existing_cover_pid" ]]; then
  [[ -n "$existing_yumed_pid" && -n "$existing_cover_pid" ]] || {
    echo "partial managed server state; run server-stop before restart" >&2
    exit 1
  }
  if [[ "$packet" == "--packet" && ! -f .android-agent/packet.enabled ]]; then
    echo "managed server is already running without packet routing" >&2
    exit 1
  fi
  echo "server already running: yumed=$existing_yumed_pid cover=$existing_cover_pid"
  exit 0
fi
if [[ -f .android-agent/packet.enabled ]]; then
  echo "managed packet routing marker remains without a live yumed; run server-stop before restart" >&2
  exit 1
fi
packet_attempted=0
cover_pid=""
yumed_pid=""
stop_started_process() {
  local name="$1" pid="$2"
  if [[ -z "$pid" ]]; then
    rm -f ".android-agent/$name.pid"
    return 0
  fi
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || return 1
    for _ in $(seq 1 50); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "failed to stop started $name PID $pid during rollback" >&2
      return 1
    fi
  fi
  wait "$pid" 2>/dev/null || true
  rm -f ".android-agent/$name.pid"
}
cleanup_failed_start() {
  status=$?
  if (( status != 0 )); then
    rollback_complete=1
    stop_started_process yumed "$yumed_pid" || rollback_complete=0
    stop_started_process cover "$cover_pid" || rollback_complete=0
    if (( packet_attempted != 0 )); then
      if ./packet-down >>.android-agent/packet-down.log 2>&1; then
        rm -f .android-agent/packet.enabled
      else
        : >.android-agent/packet.enabled
        rollback_complete=0
      fi
    fi
    if (( rollback_complete == 0 )); then
      : >.android-agent/cleanup.required
      echo "server start rollback is incomplete; preserving cleanup-required state for server-stop" >&2
    else
      rm -f .android-agent/cleanup.required
    fi
  fi
  return "$status"
}
trap cleanup_failed_start EXIT
nohup ./start-cover >.android-agent/cover.log 2>&1 &
cover_pid="$!"
echo "$cover_pid" >.android-agent/cover.pid
if [[ "$packet" == "--packet" ]]; then
  packet_attempted=1
  : >.android-agent/packet.enabled
  ./packet-up >.android-agent/packet-up.log 2>&1
fi
nohup ./start-yumed >.android-agent/yumed.log 2>&1 &
yumed_pid="$!"
echo "$yumed_pid" >.android-agent/yumed.pid
sleep 1
kill -0 "$cover_pid"
kill -0 "$yumed_pid"
trap - EXIT
echo "yumed pid=$(cat .android-agent/yumed.pid) cover pid=$(cat .android-agent/cover.pid)"
REMOTE
}

server_status() {
  local host="${1:-}" server_dir="${2:-}"
  [[ -n "$host" && -n "$server_dir" ]] || die "server-status requires local|HOST SERVER_DIR"
  server_exec "$host" "$server_dir" <<'REMOTE'
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
cd -- "$1"
for name in cover yumed; do
  pid="$(cat ".android-agent/$name.pid" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "$name running pid=$pid"
  else
    echo "$name stopped"
  fi
done
if [[ -f .android-agent/cleanup.required ]]; then
  echo "server cleanup required (marker present)"
fi
if [[ -f .android-agent/packet.enabled ]]; then
  echo "packet routing managed (marker present)"
else
  echo "packet routing disabled"
fi
tail -n 20 .android-agent/yumed.log 2>/dev/null || true
REMOTE
}

server_stop() {
  local host="${1:-}" server_dir="${2:-}" packet="${3:-}"
  [[ -n "$host" && -n "$server_dir" ]] || die "server-stop requires local|HOST SERVER_DIR"
  [[ "$packet" == "" || "$packet" == "--packet" ]] || die "unknown server-stop option: $packet"
  server_exec "$host" "$server_dir" "$packet" <<'REMOTE'
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
cd -- "$1"
packet="$2"
mkdir -p .android-agent
packet_managed=0
[[ -f .android-agent/packet.enabled ]] && packet_managed=1
if [[ "$packet" == "--packet" || "$packet_managed" == "1" ]]; then
  [[ -x ./packet-down ]] || {
    echo "packet-enabled server stop requires executable packet-down" >&2
    exit 1
  }
fi
: >.android-agent/cleanup.required
stop_managed_process() {
  local name="$1" pid command
  pid="$(cat ".android-agent/$name.pid" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    command="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    case "$name" in
      yumed) [[ "$command" == *yumed* ]] ;;
      cover) [[ "$command" == *node* || "$command" == *backend.mjs* ]] ;;
    esac || { echo "refusing stale $name PID $pid: $command" >&2; exit 1; }
    kill "$pid"
    for _ in $(seq 1 50); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "failed to stop managed $name PID $pid" >&2
      return 1
    fi
  fi
  rm -f ".android-agent/$name.pid"
}
stop_managed_process yumed
stop_managed_process cover
if [[ "$packet" == "--packet" || "$packet_managed" == "1" ]]; then
  if ./packet-down >.android-agent/packet-down.log 2>&1; then
    rm -f .android-agent/packet.enabled
  else
    echo "packet-down failed; preserving the managed marker for retry" >&2
    exit 1
  fi
fi
rm -f .android-agent/yumed.pid .android-agent/cover.pid
rm -f .android-agent/cleanup.required
REMOTE
}

main() {
  local command="${1:-help}"
  shift || true
  case "$command" in
    help|-h|--help) usage ;;
    build) build_agent "$@" ;;
    server-start) server_start "$@" ;;
    server-status) server_status "$@" ;;
    server-stop) server_stop "$@" ;;
    *)
      select_device
      case "$command" in
        doctor) doctor ;;
        install) install_agent "$@" ;;
        launch) "${ADB[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null ;;
        prepare) prepare_vpn ;;
        import) import_bundle "$@" ;;
        profiles) profiles ;;
        connect) connect_profile "$@" ;;
        disconnect) disconnect_vpn ;;
        status) status ;;
        wait) wait_phase "$@" ;;
        self-test) self_test "$@" ;;
        bench) network_bench "$@" ;;
        smoke) smoke "$@" ;;
        logs) logs ;;
        collect) collect "$@" ;;
        *) usage >&2; die "unknown command: $command" ;;
      esac
      ;;
  esac
}

main "$@"
