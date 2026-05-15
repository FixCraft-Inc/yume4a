#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SECRET_FILE_DEFAULT="$ROOT_DIR/.release-signing.env"
APK_OUTPUT_DIR="$ROOT_DIR/app/build/outputs/apk/release"
OUTPUT_DIR_DEFAULT="$APK_OUTPUT_DIR/signed"

log_section() {
  printf '\n==> %s\n' "$1"
}

log_item() {
  printf '    %-14s %s\n' "$1" "$2"
}

usage() {
  cat <<'EOF'
Usage:
  ./build.sh --keystore /path/to/release.jks --alias yume [options]

Options:
  --keystore PATH       JKS/keystore file path
  --alias NAME          signing key alias
  --store-pass VALUE    keystore password
  --key-pass VALUE      key password; defaults to --store-pass
  --secret-file PATH    local env file with signing values; default: ./.release-signing.env
  --output PATH         signed APK output directory (one signed APK per ABI is written here)
  --abi VALUE           which ABI(s) to ship. One of:
                          all          (default — every ABI: arm64-v8a, armeabi-v7a, x86, x86_64)
                          arm64-v8a    most modern phones
                          armeabi-v7a  older 32-bit ARM phones
                          x86_64       Chromebooks, Android emulators on Intel hosts
                          x86          legacy emulator/devices
                          arm          shorthand for arm64-v8a + armeabi-v7a
                          intel        shorthand for x86 + x86_64
                        comma-separated list also accepted, e.g. --abi arm64-v8a,x86_64
  --skip-clean          skip Gradle clean
  --skip-sign           build release APK only
  --help                show this help

Sources, in priority order:
  1. CLI flags
  2. Environment variables
  3. ./.release-signing.env
  4. ./gradle.properties
  5. ~/.gradle/gradle.properties

Supported signing keys:
  YUME_RELEASE_STORE_FILE
  YUME_RELEASE_KEY_ALIAS
  YUME_RELEASE_STORE_PASSWORD
  YUME_RELEASE_KEY_PASSWORD
  YUME_RELEASE_STORE_PASSWORD_B64
  YUME_RELEASE_KEY_PASSWORD_B64

Example local secret file:
  YUME_RELEASE_STORE_FILE=/home/user/keys/yume-release.jks
  YUME_RELEASE_KEY_ALIAS=yume
  YUME_RELEASE_STORE_PASSWORD_B64=$(printf 'store-password' | base64 -w0)
  YUME_RELEASE_KEY_PASSWORD_B64=$(printf 'key-password' | base64 -w0)

This file should stay local and ignored by git.
EOF
}

trim_cr() {
  printf '%s' "$1" | tr -d '\r'
}

trim_value() {
  local value
  value="$(trim_cr "$1")"
  value="$(printf '%s' "$value" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
  if [[ "$value" == \"*\" && "$value" == *\" && ${#value} -ge 2 ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && ${#value} -ge 2 ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

read_key_from_file() {
  local key="$1"
  local file="$2"
  [[ -f "$file" ]] || return 1
  local line
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -n "$line" ]] || return 1
  trim_value "${line#*=}"
}

read_gradle_property() {
  local key="$1"
  local file
  for file in "$ROOT_DIR/gradle.properties" "$HOME/.gradle/gradle.properties"; do
    read_key_from_file "$key" "$file" && return 0
  done
  return 1
}

read_local_property() {
  local key="$1"
  local value
  value="$(read_key_from_file "$key" "$ROOT_DIR/local.properties" 2>/dev/null || true)"
  [[ -n "$value" ]] || return 1
  printf '%s\n' "$value" | sed 's#\\:#:#g'
}

decode_base64() {
  local value="$1"
  [[ -n "$value" ]] || return 1
  printf '%s' "$value" | base64 --decode 2>/dev/null
}

resolve_value() {
  local plain_key="$1"
  local b64_key="$2"
  local secret_file="$3"
  local value=""

  if [[ -n "${!plain_key:-}" ]]; then
    printf '%s\n' "${!plain_key}"
    return 0
  fi
  if [[ -n "${!b64_key:-}" ]]; then
    decode_base64 "${!b64_key}"
    return 0
  fi

  value="$(read_key_from_file "$plain_key" "$secret_file" 2>/dev/null || true)"
  if [[ -n "$value" ]]; then
    printf '%s\n' "$value"
    return 0
  fi
  value="$(read_key_from_file "$b64_key" "$secret_file" 2>/dev/null || true)"
  if [[ -n "$value" ]]; then
    decode_base64 "$value"
    return 0
  fi

  value="$(read_gradle_property "$plain_key" 2>/dev/null || true)"
  if [[ -n "$value" ]]; then
    printf '%s\n' "$value"
    return 0
  fi
  value="$(read_gradle_property "$b64_key" 2>/dev/null || true)"
  if [[ -n "$value" ]]; then
    decode_base64 "$value"
    return 0
  fi

  return 1
}

resolve_sdk_dir() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return 0
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return 0
  fi
  if sdk_dir="$(read_local_property sdk.dir 2>/dev/null)"; then
    printf '%s\n' "$sdk_dir"
    return 0
  fi
  if [[ -d "$HOME/Android/Sdk" ]]; then
    printf '%s\n' "$HOME/Android/Sdk"
    return 0
  fi
  return 1
}

resolve_build_tools_dir() {
  local sdk_dir="$1"
  local dir
  dir="$(find "$sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
  [[ -n "$dir" ]] || return 1
  printf '%s\n' "$dir"
}

resolve_tool() {
  local name="$1"
  local sdk_dir="$2"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  local build_tools_dir
  build_tools_dir="$(resolve_build_tools_dir "$sdk_dir")"
  local candidate="$build_tools_dir/$name"
  [[ -x "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

SECRET_FILE="$SECRET_FILE_DEFAULT"
KEYSTORE_PATH=""
KEY_ALIAS=""
STORE_PASSWORD=""
KEY_PASSWORD=""
OUTPUT_DIR="$OUTPUT_DIR_DEFAULT"
ABI_SELECTION="all"
SKIP_CLEAN=0
SKIP_SIGN=0

ALL_ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

# Expand --abi argument into a space-separated list of concrete ABI ids.
# Accepts: all, arm, intel, a single ABI, or a comma-separated list.
resolve_abis() {
  local selection="$1"
  case "$selection" in
    all|"") printf '%s\n' "${ALL_ABIS[@]}"; return 0 ;;
    arm)    printf '%s\n' "arm64-v8a" "armeabi-v7a"; return 0 ;;
    intel)  printf '%s\n' "x86_64" "x86"; return 0 ;;
  esac

  local -a out=()
  local IFS=,
  for token in $selection; do
    token="$(trim_value "$token")"
    case "$token" in
      arm64-v8a|armeabi-v7a|x86|x86_64) out+=("$token") ;;
      "" ) ;;
      *) printf 'Unknown ABI: %s\n' "$token" >&2; return 1 ;;
    esac
  done

  if [[ "${#out[@]}" -eq 0 ]]; then
    printf 'No valid ABIs in --abi selection: %s\n' "$selection" >&2
    return 1
  fi
  printf '%s\n' "${out[@]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keystore)
      KEYSTORE_PATH="${2:-}"
      shift 2
      ;;
    --alias)
      KEY_ALIAS="${2:-}"
      shift 2
      ;;
    --store-pass)
      STORE_PASSWORD="${2:-}"
      shift 2
      ;;
    --key-pass)
      KEY_PASSWORD="${2:-}"
      shift 2
      ;;
    --secret-file)
      SECRET_FILE="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    --abi)
      ABI_SELECTION="${2:-}"
      shift 2
      ;;
    --skip-clean)
      SKIP_CLEAN=1
      shift
      ;;
    --skip-sign)
      SKIP_SIGN=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

KEYSTORE_PATH="$(trim_value "$KEYSTORE_PATH")"
KEY_ALIAS="$(trim_value "$KEY_ALIAS")"
STORE_PASSWORD="$(trim_value "$STORE_PASSWORD")"
KEY_PASSWORD="$(trim_value "$KEY_PASSWORD")"
OUTPUT_DIR="$(trim_value "$OUTPUT_DIR")"

if [[ -z "$KEYSTORE_PATH" ]]; then
  KEYSTORE_PATH="$(resolve_value YUME_RELEASE_STORE_FILE YUME_RELEASE_STORE_FILE_B64 "$SECRET_FILE" 2>/dev/null || true)"
fi
if [[ -z "$KEY_ALIAS" ]]; then
  KEY_ALIAS="$(resolve_value YUME_RELEASE_KEY_ALIAS YUME_RELEASE_KEY_ALIAS_B64 "$SECRET_FILE" 2>/dev/null || true)"
fi
if [[ -z "$STORE_PASSWORD" ]]; then
  STORE_PASSWORD="$(resolve_value YUME_RELEASE_STORE_PASSWORD YUME_RELEASE_STORE_PASSWORD_B64 "$SECRET_FILE" 2>/dev/null || true)"
fi
if [[ -z "$KEY_PASSWORD" ]]; then
  KEY_PASSWORD="$(resolve_value YUME_RELEASE_KEY_PASSWORD YUME_RELEASE_KEY_PASSWORD_B64 "$SECRET_FILE" 2>/dev/null || true)"
fi

if [[ -z "$KEY_PASSWORD" && -n "$STORE_PASSWORD" ]]; then
  KEY_PASSWORD="$STORE_PASSWORD"
fi

KEYSTORE_PATH="$(trim_value "$KEYSTORE_PATH")"
KEY_ALIAS="$(trim_value "$KEY_ALIAS")"
STORE_PASSWORD="$(trim_value "$STORE_PASSWORD")"
KEY_PASSWORD="$(trim_value "$KEY_PASSWORD")"
OUTPUT_DIR="$(trim_value "$OUTPUT_DIR")"

SDK_DIR="$(resolve_sdk_dir)"
ZIPALIGN="${ZIPALIGN:-$(resolve_tool zipalign "$SDK_DIR")}"
APKSIGNER="${APKSIGNER:-$(resolve_tool apksigner "$SDK_DIR")}"

readarray -t SELECTED_ABIS < <(resolve_abis "$ABI_SELECTION") \
  || { usage >&2; exit 1; }

log_section "Build config"
log_item "root" "$ROOT_DIR"
log_item "secret file" "$(if [[ -f "$SECRET_FILE" ]]; then printf '%s' "$SECRET_FILE"; else printf 'not found'; fi)"
log_item "keystore" "${KEYSTORE_PATH:-not set}"
log_item "alias" "${KEY_ALIAS:-not set}"
log_item "output dir" "$OUTPUT_DIR"
log_item "abi" "${SELECTED_ABIS[*]}"
log_item "zipalign" "$ZIPALIGN"
log_item "apksigner" "$APKSIGNER"
log_item "clean" "$(if [[ "$SKIP_CLEAN" -eq 0 ]]; then printf 'yes'; else printf 'no'; fi)"
log_item "sign" "$(if [[ "$SKIP_SIGN" -eq 0 ]]; then printf 'yes'; else printf 'no'; fi)"

if [[ "$SKIP_SIGN" -eq 0 ]]; then
  [[ -n "$KEYSTORE_PATH" ]] || { printf 'Missing keystore path. Use --keystore or YUME_RELEASE_STORE_FILE.\n' >&2; exit 1; }
  [[ -n "$KEY_ALIAS" ]] || { printf 'Missing key alias. Use --alias or YUME_RELEASE_KEY_ALIAS.\n' >&2; exit 1; }
  [[ -n "$STORE_PASSWORD" ]] || { printf 'Missing keystore password. Use --store-pass or YUME_RELEASE_STORE_PASSWORD(_B64).\n' >&2; exit 1; }
  [[ -n "$KEY_PASSWORD" ]] || { printf 'Missing key password. Use --key-pass or YUME_RELEASE_KEY_PASSWORD(_B64).\n' >&2; exit 1; }
  [[ -f "$KEYSTORE_PATH" ]] || { printf 'Keystore not found: %s\n' "$KEYSTORE_PATH" >&2; exit 1; }
  [[ -x "$ZIPALIGN" ]] || { printf 'zipalign not found: %s\n' "$ZIPALIGN" >&2; exit 1; }
  [[ -x "$APKSIGNER" ]] || { printf 'apksigner not found: %s\n' "$APKSIGNER" >&2; exit 1; }
fi

GRADLE_TASKS=(syncBasefwxJava :app:assembleRelease)
if [[ "$SKIP_CLEAN" -eq 0 ]]; then
  GRADLE_TASKS=(clean "${GRADLE_TASKS[@]}")
fi

# When only a subset of ABIs is requested, pass it to Gradle so the native
# build / packaging skips work for the unselected ABIs. The build.gradle.kts
# splits.abi block reads this property.
GRADLE_ARGS=()
if [[ "${#SELECTED_ABIS[@]}" -ne ${#ALL_ABIS[@]} ]]; then
  GRADLE_ARGS+=("-PyumeAbi=$(IFS=,; printf '%s' "${SELECTED_ABIS[*]}")")
fi

log_section "Gradle build"
./gradlew "${GRADLE_TASKS[@]}" "${GRADLE_ARGS[@]}"

# ABI splits produce one unsigned APK per ABI under $APK_OUTPUT_DIR. Filter the
# discovered set down to the requested ABIs so --abi selects which APKs get
# zipaligned, signed, and reported.
shopt -s nullglob
ALL_UNSIGNED=("$APK_OUTPUT_DIR"/app-*-release-unsigned.apk)
shopt -u nullglob

declare -A SELECTED_ABI_SET=()
for abi in "${SELECTED_ABIS[@]}"; do
  SELECTED_ABI_SET[$abi]=1
done

UNSIGNED_APKS=()
for apk in "${ALL_UNSIGNED[@]}"; do
  base="$(basename "$apk")"
  for abi in "${SELECTED_ABIS[@]}"; do
    if [[ "$base" == app-"$abi"-release-unsigned.apk ]]; then
      UNSIGNED_APKS+=("$apk")
      break
    fi
  done
done

if [[ "${#UNSIGNED_APKS[@]}" -eq 0 ]]; then
  printf 'No unsigned release APKs matched --abi=%s in %s.\n' "$ABI_SELECTION" "$APK_OUTPUT_DIR" >&2
  printf 'Available:\n' >&2
  for apk in "${ALL_UNSIGNED[@]}"; do printf '  %s\n' "$apk" >&2; done
  exit 1
fi

if [[ "$SKIP_SIGN" -eq 1 ]]; then
  log_section "Done"
  for apk in "${UNSIGNED_APKS[@]}"; do
    printf '%s  %s\n' "$(du -h "$apk" | cut -f1)" "$apk"
  done
  exit 0
fi

mkdir -p "$OUTPUT_DIR"

declare -a SIGNED_OUTPUTS=()
for unsigned in "${UNSIGNED_APKS[@]}"; do
  base="$(basename "$unsigned")"
  # Strip the -unsigned suffix to derive ABI-tagged signed name.
  signed_name="${base/-unsigned/}"
  signed="$OUTPUT_DIR/$signed_name"
  aligned="${unsigned%.apk}-aligned.apk"

  log_section "Zipalign  ${base}"
  "$ZIPALIGN" -p -f 4 "$unsigned" "$aligned"

  log_section "Sign       ${signed_name}"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$signed" \
    "$aligned"
  rm -f "$aligned"
  SIGNED_OUTPUTS+=("$signed")
done

log_section "Verify"
for signed in "${SIGNED_OUTPUTS[@]}"; do
  "$APKSIGNER" verify --print-certs "$signed" >/dev/null
  printf '    ok  %s\n' "$signed"
done

log_section "Done"
for signed in "${SIGNED_OUTPUTS[@]}"; do
  printf '%s  %s\n' "$(du -h "$signed" | cut -f1)" "$signed"
done
