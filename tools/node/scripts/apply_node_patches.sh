#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PATCH_DIR="$ROOT_DIR/tools/patches"
NODE_DIR="$ROOT_DIR/node"

if [ ! -d "$PATCH_DIR" ]; then
  echo "Patch dir not found: $PATCH_DIR" >&2
  exit 1
fi

# Patch set purpose:
# - avoid-ficlone-ioctl.patch: Android FS lacks FICLONE on some devices.
# - deps-uv-uv.gyp.patch: Keep include dirs + enable GNU extensions for libuv.
# - tools-v8_gypfiles-v8.gyp.patch: Ensure -latomic on Android clang builds.
# - tools-gyp-pylib-gyp-generator-ninja.py.patch: Split host/target gen outputs.
for patch in "$PATCH_DIR"/*.patch; do
  name="$(basename "$patch")"
  echo "Applying $name"
  if patch -p1 -N -d "$ROOT_DIR/node" < "$patch"; then
    continue
  fi
  # If already applied, patch will often report reversed/previously applied.
  if patch -p1 -N -R --dry-run -d "$ROOT_DIR/node" < "$patch" >/dev/null 2>&1; then
    echo "Notice: patch already applied, skipping: $name" >&2
    continue
  fi
  echo "Error: patch failed to apply: $name" >&2
  exit 1
done
