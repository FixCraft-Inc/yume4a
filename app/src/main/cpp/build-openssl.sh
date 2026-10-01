#!/usr/bin/env bash
# Build OpenSSL 3.5.x static libs for one Android ABI, cache the
# result under app/build/openssl/<abi>/.
#
# Usage:  build-openssl.sh <ABI> <ANDROID_NDK_HOME> <ANDROID_API> <OUTPUT_ROOT>
#
# Idempotent only for an identical version, source hash, NDK, API level, ABI,
# and script revision. A stale dependency cache is never reused after one of
# those inputs changes.
#
# Why a shell script instead of CMake's ExternalProject_Add: Android
# Gradle Plugin only allows ONE externalNativeBuild path per module
# (we already use ndk-build for the shared YUME JNI adapter), so the OpenSSL build
# has to happen OUTSIDE the AGP native-build phase. This script is
# invoked by a Gradle task that runs before externalNativeBuild starts.

set -euo pipefail

ABI="${1:?missing ABI}"
NDK_HOME="${2:?missing ANDROID_NDK_HOME}"
API_LEVEL="${3:?missing ANDROID_API}"
OUTPUT_ROOT="${4:?missing OUTPUT_ROOT}"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.7}"
OPENSSL_URL="https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
# SHA256 for the official OpenSSL 3.5.7 release. A different version requires
# an explicitly supplied hash; release builds must never disable verification.
OPENSSL_SHA256_DEFAULT="a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8"
OPENSSL_SHA256="${OPENSSL_SHA256:-$OPENSSL_SHA256_DEFAULT}"

case "$ABI" in
    arm64-v8a)    OPENSSL_TARGET="android-arm64";    NDK_TRIPLE="aarch64-linux-android";;
    armeabi-v7a)  OPENSSL_TARGET="android-arm";      NDK_TRIPLE="armv7a-linux-androideabi";;
    x86_64)       OPENSSL_TARGET="android-x86_64";   NDK_TRIPLE="x86_64-linux-android";;
    x86)          OPENSSL_TARGET="android-x86";      NDK_TRIPLE="i686-linux-android";;
    *)            echo "Unsupported ABI: $ABI" >&2; exit 2;;
esac

INSTALL_DIR="${OUTPUT_ROOT}/${ABI}"
STAMP_FILE="${INSTALL_DIR}/.yume-openssl-build-stamp"
mkdir -p "${OUTPUT_ROOT}"

# Detect host triple for the NDK toolchain.
HOST_OS="$(uname -s)"
case "$HOST_OS" in
    Linux)   NDK_HOST_TAG="linux-x86_64";;
    Darwin)  NDK_HOST_TAG="darwin-x86_64";;
    *)       echo "Unsupported NDK host: $HOST_OS" >&2; exit 2;;
esac

NDK_TOOLCHAIN_PATH="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}"
if [[ ! -d "${NDK_TOOLCHAIN_PATH}" ]]; then
    echo "NDK toolchain not found at ${NDK_TOOLCHAIN_PATH}" >&2
    exit 2
fi

NDK_REVISION="$(sed -n 's/^Pkg.Revision *= *//p' "${NDK_HOME}/source.properties" | head -n 1)"
SCRIPT_SHA256="$(sha256sum "$0" | awk '{print $1}')"
EXPECTED_STAMP=$(printf '%s\n' \
    "openssl_version=${OPENSSL_VERSION}" \
    "openssl_sha256=${OPENSSL_SHA256}" \
    "abi=${ABI}" \
    "api=${API_LEVEL}" \
    "ndk_revision=${NDK_REVISION}" \
    "script_sha256=${SCRIPT_SHA256}")
if [[ -f "${INSTALL_DIR}/lib/libssl.a" && -f "${INSTALL_DIR}/lib/libcrypto.a" &&
      -f "${STAMP_FILE}" && "$(cat "${STAMP_FILE}")" == "${EXPECTED_STAMP}" ]]; then
    echo "openssl-${OPENSSL_VERSION} for ${ABI} already installed at ${INSTALL_DIR}"
    exit 0
fi
rm -rf "${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"

SRC_ROOT="${OUTPUT_ROOT}/src"
ARCHIVE="${SRC_ROOT}/openssl-${OPENSSL_VERSION}.tar.gz"
mkdir -p "${SRC_ROOT}"

if [[ ! -f "${ARCHIVE}" ]]; then
    echo "Downloading openssl-${OPENSSL_VERSION} ..."
    curl --fail --silent --show-error --location -o "${ARCHIVE}" "${OPENSSL_URL}"
fi

if [[ "${OPENSSL_SHA256_SKIP:-0}" == "1" && "${YUME_ALLOW_INSECURE_DEP_OVERRIDE:-0}" != "1" ]]; then
    echo "OPENSSL_SHA256_SKIP is forbidden for normal and release builds." >&2
    echo "Set YUME_ALLOW_INSECURE_DEP_OVERRIDE=1 only for isolated local diagnosis." >&2
    exit 3
fi
if [[ "${OPENSSL_SHA256_SKIP:-0}" != "1" ]]; then
    ACTUAL_SHA="$(sha256sum "${ARCHIVE}" | awk '{print $1}')"
    if [[ "${ACTUAL_SHA}" != "${OPENSSL_SHA256}" ]]; then
        echo "OpenSSL tarball SHA256 mismatch:" >&2
        echo "  expected: ${OPENSSL_SHA256}" >&2
        echo "  actual:   ${ACTUAL_SHA}" >&2
        echo "Set OPENSSL_SHA256 to the official hash for an intentional version update." >&2
        exit 3
    fi
fi

# Extract into a per-ABI scratch dir so parallel-per-ABI builds don't
# collide on the same source tree.
BUILD_DIR="${OUTPUT_ROOT}/build/${ABI}"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
tar -xzf "${ARCHIVE}" -C "${BUILD_DIR}"
cd "${BUILD_DIR}/openssl-${OPENSSL_VERSION}"

# Put the NDK clang on PATH and invoke OpenSSL's Configure script.
export ANDROID_NDK_ROOT="${NDK_HOME}"
export ANDROID_NDK_HOME="${NDK_HOME}"
export PATH="${NDK_TOOLCHAIN_PATH}/bin:${PATH}"

echo "Configuring openssl-${OPENSSL_VERSION} for ${ABI} (target=${OPENSSL_TARGET}) ..."
./Configure "${OPENSSL_TARGET}" \
    -D__ANDROID_API__="${API_LEVEL}" \
    no-shared no-tests no-ui-console no-engine no-dynamic-engine \
    no-comp no-async no-srp no-psk no-weak-ssl-ciphers \
    no-deprecated no-zlib no-zlib-dynamic no-padlockeng \
    no-blake2 no-md2 no-md4 no-mdc2 no-rmd160 \
    no-rc2 no-rc4 no-rc5 no-bf no-cast no-idea \
    no-camellia no-seed no-aria no-sm2 no-sm3 no-sm4 \
    no-dh no-dsa no-gost no-ec2m \
    no-cms no-ocsp no-ts no-cmp no-srtp no-ssl-trace \
    no-dtls no-dtls1 no-dtls1-method no-ssl3 no-ssl3-method \
    no-cmac no-ocb no-siphash no-siv no-rfc3779 no-nextprotoneg \
    --prefix="${INSTALL_DIR}" \
    --openssldir="${INSTALL_DIR}/ssl" \
    > "${INSTALL_DIR}.configure.log" 2>&1 || {
        echo "OpenSSL Configure failed; see ${INSTALL_DIR}.configure.log" >&2
        tail -40 "${INSTALL_DIR}.configure.log" >&2
        exit 4
    }

JOBS="$(nproc 2>/dev/null || echo 4)"
echo "Building openssl libs for ${ABI} with -j${JOBS} ..."
make -j"${JOBS}" build_libs > "${INSTALL_DIR}.build.log" 2>&1 || {
    echo "OpenSSL build failed; see ${INSTALL_DIR}.build.log" >&2
    tail -40 "${INSTALL_DIR}.build.log" >&2
    exit 5
}

echo "Installing openssl libs into ${INSTALL_DIR} ..."
make install_dev > "${INSTALL_DIR}.install.log" 2>&1 || {
    echo "OpenSSL install failed; see ${INSTALL_DIR}.install.log" >&2
    tail -40 "${INSTALL_DIR}.install.log" >&2
    exit 6
}

# Clean the build dir to save disk; keep the source tarball for re-runs.
cd "${OUTPUT_ROOT}"
rm -rf "${BUILD_DIR}"

if [[ ! -f "${INSTALL_DIR}/lib/libssl.a" || ! -f "${INSTALL_DIR}/lib/libcrypto.a" ]]; then
    echo "Expected libs not produced under ${INSTALL_DIR}/lib" >&2
    ls -la "${INSTALL_DIR}/lib/" >&2 || true
    exit 7
fi

printf '%s\n' "${EXPECTED_STAMP}" > "${STAMP_FILE}"

echo "openssl-${OPENSSL_VERSION} for ${ABI} ready at ${INSTALL_DIR}"
