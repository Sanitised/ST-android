#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-}"
if [ -z "${TARGET_DIR}" ]; then
  echo "Usage: $0 <dir>"
  exit 1
fi

if [ ! -d "${TARGET_DIR}" ]; then
  echo "Directory not found: ${TARGET_DIR}"
  exit 1
fi

if find "${TARGET_DIR}/node_modules" -name "*.node" -print -quit | grep -q .; then
  echo "Native addons detected (.node files). Aborting."
  find "${TARGET_DIR}/node_modules" -name "*.node" -print
  exit 1
fi

if find "${TARGET_DIR}/node_modules" -name "binding.gyp" -print -quit | grep -q .; then
  echo "Native addon build scripts detected (binding.gyp). Aborting."
  find "${TARGET_DIR}/node_modules" -name "binding.gyp" -print
  exit 1
fi
