#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="st-android-build:local"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${ROOT_DIR}"

docker build -f ci/docker/Dockerfile -t "${IMAGE_NAME}" .

DOCKER_ENV_ARGS=()
if [ -n "${VERSION_NAME:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "VERSION_NAME=${VERSION_NAME}")
fi
if [ -n "${VERSION_CODE:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "VERSION_CODE=${VERSION_CODE}")
fi

docker run --rm \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  "${DOCKER_ENV_ARGS[@]}" \
  "${IMAGE_NAME}" \
  bash -lc '\
    set -euo pipefail; \
    NDK_ROOT="${ANDROID_NDK_HOME:-}"; \
    if [ -z "$NDK_ROOT" ]; then \
      NDK_ROOT="$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | head -n1 || true)"; \
    fi; \
    if [ -z "$NDK_ROOT" ]; then \
      NDK_ROOT="$(ls -d /opt/android-sdk-linux/ndk/* 2>/dev/null | head -n1 || true)"; \
    fi; \
    if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then \
      echo "Android NDK not found in container"; \
      exit 1; \
    fi; \
    TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"; \
    if [ ! -d "$TOOLCHAIN" ]; then \
      echo "NDK toolchain not found at $TOOLCHAIN"; \
      exit 1; \
    fi; \
    export ANDROID_NDK_HOME="$NDK_ROOT"; \
    export ANDROID_API=26; \
    ABI_TRIPLE="aarch64-linux-android"; \
    SYSROOT_LIB="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"; \
    LIBCXX_PATH="$SYSROOT_LIB/$ABI_TRIPLE/$ANDROID_API/libc++_shared.so"; \
    if [ ! -f "$LIBCXX_PATH" ]; then \
      LIBCXX_PATH="$SYSROOT_LIB/$ABI_TRIPLE/libc++_shared.so"; \
    fi; \
    if [ ! -f "$LIBCXX_PATH" ]; then \
      echo "Expected libc++_shared.so not found for $ABI_TRIPLE (API $ANDROID_API) under $SYSROOT_LIB"; \
      echo "Candidates under sysroot:"; \
      find "$SYSROOT_LIB" -path "*/libc++_shared.so" -print || true; \
      exit 1; \
    fi; \
    python3 ci/scripts/check_elf_align.py "$TOOLCHAIN/bin/llvm-readelf" "$LIBCXX_PATH"; \
    ./tools/node/scripts/build_node_android.sh arm64; \
    mkdir -p app/src/main/jniLibs/arm64-v8a; \
    cp out/android/arm64/node app/src/main/jniLibs/arm64-v8a/libnode.so; \
    cp "$LIBCXX_PATH" app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
    python3 ci/scripts/check_elf_align.py "$TOOLCHAIN/bin/llvm-readelf" \
      app/src/main/jniLibs/arm64-v8a/libnode.so \
      app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
    bash ci/scripts/build_st_bundle.sh; \
    gradle :app:assembleDebug \
  '

printf '\nAPK: %s\n' "${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
