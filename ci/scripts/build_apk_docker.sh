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
if [ -n "${RELEASE_KEYSTORE_B64:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEYSTORE_B64=${RELEASE_KEYSTORE_B64}")
fi
if [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_STORE_PASSWORD=${RELEASE_STORE_PASSWORD}")
fi
if [ -n "${RELEASE_KEY_ALIAS:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEY_ALIAS=${RELEASE_KEY_ALIAS}")
fi
if [ -n "${RELEASE_KEY_PASSWORD:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD}")
fi
if [ -n "${GITHUB_REF_TYPE:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF_TYPE=${GITHUB_REF_TYPE}")
fi
if [ -n "${GITHUB_REF_NAME:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF_NAME=${GITHUB_REF_NAME}")
fi
if [ -n "${GITHUB_REF:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF=${GITHUB_REF}")
fi
if [ -n "${GITHUB_RUN_NUMBER:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_RUN_NUMBER=${GITHUB_RUN_NUMBER}")
fi

docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  "${DOCKER_ENV_ARGS[@]}" \
  "${IMAGE_NAME}" \
  bash -lc '\
    set -euo pipefail; \
    export HOME="/workspace/.home"; \
    export NPM_CONFIG_CACHE="${NPM_CONFIG_CACHE:-/tmp/.npm}"; \
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"; \
    export ANDROID_SDK_HOME="/workspace/.android"; \
    export ANDROID_PREFS_ROOT="/workspace/.android"; \
    export ANDROID_USER_HOME="/workspace/.android"; \
    mkdir -p "$HOME" "$NPM_CONFIG_CACHE" "$GRADLE_USER_HOME" "$ANDROID_SDK_HOME"; \
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"; \
    if [ -z "$SDK_ROOT" ]; then \
      for candidate in /opt/android-sdk-linux /opt/android-sdk /usr/lib/android-sdk /sdk; do \
        if [ -d "$candidate" ]; then \
          SDK_ROOT="$candidate"; \
          break; \
        fi; \
      done; \
    fi; \
    if [ -z "$SDK_ROOT" ]; then \
      echo "Android SDK root not found in container"; \
      exit 1; \
    fi; \
    export ANDROID_SDK_ROOT="$SDK_ROOT"; \
    export ANDROID_HOME="$SDK_ROOT"; \
    BUILD_MODE="debug"; \
    if [ -n "${RELEASE_KEYSTORE_B64:-}" ]; then \
      mkdir -p ci/keystore; \
      echo "$RELEASE_KEYSTORE_B64" | base64 -d > ci/keystore/release.jks; \
      chmod 600 ci/keystore/release.jks; \
      export RELEASE_STORE_FILE="/workspace/ci/keystore/release.jks"; \
      if [ -z "${RELEASE_KEY_PASSWORD:-}" ] && [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then \
        export RELEASE_KEY_PASSWORD="$RELEASE_STORE_PASSWORD"; \
      fi; \
      BUILD_MODE="release"; \
    fi; \
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
    if [ "$BUILD_MODE" = "release" ]; then \
      gradle :app:assembleRelease; \
    else \
      gradle :app:assembleDebug; \
    fi; \
  '

APK_PATH=""
if [ -f "${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk" ]; then
  APK_PATH="${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk"
elif [ -f "${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk" ]; then
  APK_PATH="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ -n "${APK_PATH}" ] && [[ "${APK_PATH}" == */release/* ]]; then
  VERSION_LABEL="${VERSION_NAME:-}"
  if [ -z "${VERSION_LABEL}" ] && [ -n "${GITHUB_REF_NAME:-}" ]; then
    VERSION_LABEL="${GITHUB_REF_NAME#refs/tags/}"
  fi
  VERSION_LABEL="${VERSION_LABEL#v}"
  if [ -n "${VERSION_LABEL}" ]; then
    mkdir -p "${ROOT_DIR}/out"
    RELEASE_NAME="ST-android-${VERSION_LABEL}.apk"
    cp -f "${APK_PATH}" "${ROOT_DIR}/out/${RELEASE_NAME}"
    printf '\nAPK: %s\n' "${ROOT_DIR}/out/${RELEASE_NAME}"
    exit 0
  fi
fi

if [ -n "${APK_PATH}" ]; then
  printf '\nAPK: %s\n' "${APK_PATH}"
else
  printf '\nAPK not found\n'
  exit 1
fi
