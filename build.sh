#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SECRET_FILE_DEFAULT="$ROOT_DIR/.release-signing.env"
OUTPUT_APK_DEFAULT="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
UNSIGNED_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release-aligned.apk"
SIGNED_APK_DEFAULT="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

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
  --output PATH         signed APK output path
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
OUTPUT_APK="$OUTPUT_APK_DEFAULT"
SKIP_CLEAN=0
SKIP_SIGN=0

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
      OUTPUT_APK="${2:-}"
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
OUTPUT_APK="$(trim_value "$OUTPUT_APK")"

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
OUTPUT_APK="$(trim_value "$OUTPUT_APK")"

SDK_DIR="$(resolve_sdk_dir)"
ZIPALIGN="${ZIPALIGN:-$(resolve_tool zipalign "$SDK_DIR")}"
APKSIGNER="${APKSIGNER:-$(resolve_tool apksigner "$SDK_DIR")}"

log_section "Build config"
log_item "root" "$ROOT_DIR"
log_item "secret file" "$(if [[ -f "$SECRET_FILE" ]]; then printf '%s' "$SECRET_FILE"; else printf 'not found'; fi)"
log_item "keystore" "${KEYSTORE_PATH:-not set}"
log_item "alias" "${KEY_ALIAS:-not set}"
log_item "output" "$OUTPUT_APK"
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

log_section "Gradle build"
./gradlew "${GRADLE_TASKS[@]}"

if [[ "$SKIP_SIGN" -eq 1 ]]; then
  if [[ -f "$SIGNED_APK_DEFAULT" ]]; then
    log_section "Done"
    printf 'Release APK: %s\n' "$SIGNED_APK_DEFAULT"
    exit 0
  fi
  if [[ -f "$UNSIGNED_APK" ]]; then
    log_section "Done"
    printf 'Unsigned release APK: %s\n' "$UNSIGNED_APK"
    exit 0
  fi
  printf 'Release APK not found after assembleRelease.\n' >&2
  exit 1
fi

[[ -f "$UNSIGNED_APK" ]] || { printf 'Unsigned release APK not found: %s\n' "$UNSIGNED_APK" >&2; exit 1; }

mkdir -p "$(dirname "$OUTPUT_APK")"
rm -f "$ALIGNED_APK" "$OUTPUT_APK"

log_section "Zipalign"
"$ZIPALIGN" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

log_section "Signing"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASSWORD" \
  --key-pass "pass:$KEY_PASSWORD" \
  --out "$OUTPUT_APK" \
  "$ALIGNED_APK"

log_section "Verify"
"$APKSIGNER" verify --verbose --print-certs "$OUTPUT_APK"

log_section "Done"
printf 'Signed release APK: %s\n' "$OUTPUT_APK"
