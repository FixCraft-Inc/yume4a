#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="${YUME_ANDROID_AGENT_PACKAGE:-com.fixcraft.yume.agent}"
# The package name is interpolated into device shell commands below.
if [[ ! "$PACKAGE" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
  printf 'invalid YUME_ANDROID_AGENT_PACKAGE\n' >&2
  exit 2
fi
COMMAND_ACTIVITY="$PACKAGE/com.fixcraft.yume.agent.AgentCommandActivity"
CONSENT_ACTIVITY="$PACKAGE/com.fixcraft.yume.agent.AgentVpnConsentActivity"
PRIVATE_DIR="no_backup/adb-agent"
SNAPSHOT_FILE="$PRIVATE_DIR/snapshot.json"
CONSENT_FILE="$PRIVATE_DIR/consent.json"
DEFAULT_ABI="arm64-v8a"
# The largest sealed kit (docs/protocol/SEALED_KIT_1.md in YUME).
MAX_KIT_BYTES=$((16 + 12 + 4 + 1024 * 1024 + 1024 + 16))
MAX_CODE_BYTES=128

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
  import KIT_FILE                Import a sealed kit. Its code is read from stdin
  kits                           List the stored kits (names and endpoints only)
  delete KIT_ID                  Remove a stored kit
  connect [KIT_ID]               Connect the named kit, or the selected one
  disconnect
  status                         Print the current machine-readable runtime snapshot
  wait PHASE [TIMEOUT_SECONDS]   Wait for Idle/Connecting/Connected/Reconnecting/Error

Tests/telemetry:
  bench URL [MAX_BYTES] [TIMEOUT_MS]
                                 Download through the VPN and report timing/throughput
  smoke [KIT_ID] [URL]           Connect, optionally bench, print status, disconnect
  logs                           Print Yume logcat plus the structured event log
  collect [OUTPUT_DIR]           Capture state, logs, package/device/memory evidence

Environment:
  ANDROID_SERIAL                 Select a device when more than one is attached
  YUME_ANDROID_AGENT_PACKAGE     Override the default com.fixcraft.yume.agent
  YUME_UNVERIFIED_SOURCE=1       Permit unverified or dirty YUME test sources.
                                 Testing only: never release that APK.

A kit's code is never accepted as a command-line argument. `import` reads it
from stdin and hands it to the app as a one-shot private file, which the app
deletes as it reads it. The server side is YUME's own: create a kit with
`yume-setup`, run `yumed`, and seal the client directory with
`yume --seal-kit` (docs/QUICKSTART.md in YUME).
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

# adb joins arguments into a command interpreted by the device's POSIX shell.
# Quote every argument, including embedded apostrophes, before that join.
device_shell() {
  local command="" argument
  for argument in "$@"; do
    argument="${argument//\'/\'\\\'\'}"
    command+="'${argument}' "
  done
  "${ADB[@]}" shell "$command"
}

run_as() {
  device_shell run-as "$PACKAGE" "$@"
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
  # adb shell's protocol carries the end of stdin as its own message. exec-in
  # closes the whole stream instead, and Android 17 then ended the remote
  # process before cat finished or the rename ran.
  if ! "${ADB[@]}" shell \
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
  device_shell am start -W --activity-multiple-task \
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
  local extra=()
  if [[ "${YUME_UNVERIFIED_SOURCE:-0}" == "1" ]]; then
    extra+=("-PyumeUnverifiedSource=true")
  fi
  GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx4g}" \
    ./gradlew --no-daemon --max-workers=2 --console=plain \
      :app:assembleDirectAgent "-PyumeAbi=$abi" "${extra[@]}"
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
  device_shell am start -W -n "$CONSENT_ACTIVITY" >/dev/null
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

import_kit() {
  local kit="${1:-}"
  [[ -f "$kit" ]] || die "kit file not found: $kit"
  local kit_bytes command_id kit_file code_file
  kit_bytes="$(stat -c '%s' -- "$kit")" || die "could not inspect kit file: $kit"
  [[ "$kit_bytes" =~ ^[0-9]+$ ]] || die "invalid kit file size: $kit"
  (( kit_bytes >= 1 && kit_bytes <= MAX_KIT_BYTES )) || \
    die "a sealed kit is between 1 and $MAX_KIT_BYTES bytes"
  command_id="$(new_command_id)"
  kit_file="$PRIVATE_DIR/import-$command_id.kit"
  code_file="$PRIVATE_DIR/import-code-$command_id"
  (
    trap 'clear_private "$kit_file" || true; clear_private "$kit_file.partial" || true; clear_private "$code_file" || true; clear_private "$code_file.partial" || true' EXIT
    local code code_bytes result
    IFS= read -r -s code || true
    [[ -n "$code" ]] || die "read the kit's code from stdin"
    code_bytes="$(LC_ALL=C printf '%s' "$code" | wc -c)"
    (( code_bytes <= MAX_CODE_BYTES )) || die "the code exceeds $MAX_CODE_BYTES bytes"
    write_private_stdin "$kit_file" < "$kit"
    printf '%s' "$code" | write_private_stdin "$code_file"
    unset code
    result="$(send_command_with_id "$command_id" IMPORT_KIT 180)"
    require_ok "$result"
    jq . <<<"$result"
  )
}

kits() {
  local result
  result="$(send_command KITS 30)"
  require_ok "$result"
  jq '.data' <<<"$result"
}

delete_kit() {
  local kit_id="${1:-}"
  [[ "$kit_id" =~ ^[A-Za-z0-9._-]{1,96}$ ]] || die "invalid KIT_ID"
  local result
  result="$(send_command DELETE_KIT 30 --es kit_id "$kit_id")"
  require_ok "$result"
  jq . <<<"$result"
}

connect_kit() {
  local kit_id="${1:-}"
  [[ -z "${2:-}" ]] || die "connect accepts at most one KIT_ID"
  [[ -z "$kit_id" || "$kit_id" =~ ^[A-Za-z0-9._-]{1,96}$ ]] || die "invalid KIT_ID"
  local result
  if [[ -n "$kit_id" ]]; then
    result="$(send_command CONNECT 30 --es kit_id "$kit_id")"
  else
    result="$(send_command CONNECT 30)"
  fi
  require_ok "$result"
  jq . <<<"$result"
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
  [[ "$wanted" =~ ^(Idle|PermissionRequired|Connecting|Connected|Reconnecting|Disconnecting|Error)$ ]] || die "invalid VPN phase"
  [[ "$timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] || die "invalid wait timeout"
  (( timeout_seconds <= 3600 )) || die "wait timeout exceeds 3600 seconds"
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

network_bench() {
  local url="${1:-}"
  local max_bytes="${2:-67108864}"
  local timeout_ms="${3:-30000}"
  [[ -n "$url" ]] || die "bench requires URL"
  [[ "$max_bytes" =~ ^[1-9][0-9]{0,9}$ ]] || die "invalid benchmark byte limit"
  (( max_bytes >= 1024 && max_bytes <= 1073741824 )) || die "benchmark byte limit outside 1024..1073741824"
  [[ "$timeout_ms" =~ ^[1-9][0-9]{0,5}$ ]] || die "invalid benchmark timeout"
  (( timeout_ms >= 1000 && timeout_ms <= 180000 )) || die "benchmark timeout outside 1000..180000"
  local wait_seconds=$((timeout_ms / 1000 + 45)) result
  result="$(send_command NETWORK_BENCH "$wait_seconds" --es url "$url" --el max_bytes "$max_bytes" --el timeout_ms "$timeout_ms")"
  require_ok "$result"
  jq . <<<"$result"
}

smoke() {
  local kit_id="" url=""
  # One argument that looks like a URL is the URL, with the selected kit.
  if [[ "${1:-}" == http://* || "${1:-}" == https://* ]]; then
    url="$1"
  else
    kit_id="${1:-}"
    url="${2:-}"
  fi
  local connected=0
  cleanup_smoke() {
    if (( connected )); then
      disconnect_vpn >/dev/null 2>&1 || true
    fi
  }
  trap cleanup_smoke EXIT INT TERM
  connect_kit "$kit_id"
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
  kits > "$output/kits.json"
  doctor > "$output/device.json"
  "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$output/meminfo.txt"
  "${ADB[@]}" shell dumpsys package "$PACKAGE" > "$output/package.txt"
  logs > "$output/logs.txt"
  printf '%s\n' "$output"
}

main() {
  local command="${1:-help}"
  shift || true
  case "$command" in
    help|-h|--help) usage ;;
    build) build_agent "$@" ;;
    *)
      select_device
      case "$command" in
        doctor) doctor ;;
        install) install_agent "$@" ;;
        launch) "${ADB[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null ;;
        prepare) prepare_vpn ;;
        import) import_kit "$@" ;;
        kits) kits ;;
        delete) delete_kit "$@" ;;
        connect) connect_kit "$@" ;;
        disconnect) disconnect_vpn ;;
        status) status ;;
        wait) wait_phase "$@" ;;
        bench) network_bench "$@" ;;
        smoke) smoke "$@" ;;
        logs) logs ;;
        collect) collect "$@" ;;
        *) usage >&2; die "unknown command: $command" ;;
      esac
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
