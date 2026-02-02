#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ARCH="${1:-arm64}"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "ANDROID_NDK_HOME is not set" >&2
  exit 1
fi

case "$ARCH" in
  arm64|aarch64)
    DEST_CPU="arm64"
    TOOLCHAIN_PREFIX="aarch64-linux-android"
    ;;
  arm|armv7)
    DEST_CPU="arm"
    TOOLCHAIN_PREFIX="armv7a-linux-androideabi"
    ;;
  x86|i686)
    DEST_CPU="ia32"
    TOOLCHAIN_PREFIX="i686-linux-android"
    ;;
  x64|x86_64)
    DEST_CPU="x64"
    TOOLCHAIN_PREFIX="x86_64-linux-android"
    ;;
  *)
    echo "Unsupported arch: $ARCH" >&2
    exit 1
    ;;
esac

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
  echo "NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${ANDROID_API}-clang++"
export LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384 -Wl,-z,separate-loadable-segments"
export GYP_DEFINES="host_os=linux android_ndk_path=$ANDROID_NDK_HOME"
export CC_host="${CC_host:-gcc}"
export CXX_host="${CXX_host:-g++}"
export LINK_host="${LINK_host:-g++}"

cd "$ROOT_DIR/node"

"$ROOT_DIR/tools/node/scripts/apply_node_patches.sh"

./configure \
  --dest-cpu="$DEST_CPU" \
  --dest-os=android \
  --cross-compiling \
  --openssl-no-asm \
  --ninja

NINJA_JOBS="${NINJA_JOBS:-4}"
ninja -C out/Release -j "$NINJA_JOBS" node

mkdir -p "$ROOT_DIR/out/android/$ARCH"
cp -f "$ROOT_DIR/node/out/Release/node" "$ROOT_DIR/out/android/$ARCH/node"

printf "\nNode binary written to %s\n" "$ROOT_DIR/out/android/$ARCH/node"
