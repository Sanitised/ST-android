#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ST_DIR="${ROOT_DIR}/SillyTavern"
OUT_DIR="${ROOT_DIR}/out/st_bundle"
STAGE_DIR="${OUT_DIR}/st"
ASSET_DIR="${ROOT_DIR}/app/src/main/assets/node_payload"
LEGAL_ASSET_DIR="${ROOT_DIR}/app/src/main/assets/legal"

if [ ! -d "${ST_DIR}" ]; then
  echo "SillyTavern submodule not found at ${ST_DIR}"
  exit 1
fi

rm -rf "${OUT_DIR}"
mkdir -p "${STAGE_DIR}"

tar -C "${ST_DIR}" -cf - \
  --exclude=".git" \
  --exclude="node_modules" \
  --exclude="tests" \
  --exclude="backups" \
  --exclude="data" \
  --exclude=".github" \
  --exclude=".vscode" \
  --exclude="docker" \
  --exclude="colab" \
  . \
  | tar -C "${STAGE_DIR}" -xf -

cd "${STAGE_DIR}"
npm_cache_dir="${NPM_CONFIG_CACHE:-${TMPDIR:-/tmp}/.npm}"
export NPM_CONFIG_CACHE="${npm_cache_dir}"
mkdir -p "${npm_cache_dir}"
npm ci --omit=dev --ignore-scripts

"${SCRIPT_DIR}/audit_no_native_addons.sh" "${STAGE_DIR}"

cd "${ROOT_DIR}"
mkdir -p "${ASSET_DIR}"
mkdir -p "${LEGAL_ASSET_DIR}"
rm -f "${ASSET_DIR}/st_bundle.tar" "${ASSET_DIR}/st_bundle.tar.gz"

if [ ! -f "${STAGE_DIR}/package-lock.json" ]; then
  echo "Expected package-lock.json not found at ${STAGE_DIR}/package-lock.json"
  exit 1
fi
cp -f "${STAGE_DIR}/package-lock.json" "${LEGAL_ASSET_DIR}/sillytavern_package-lock.json"

tar -C "${OUT_DIR}" -cf "${ASSET_DIR}/st_bundle.tar" st
# Bundle npm for runtime use (needed for custom ST version installation)
NPM_DIR=""
if command -v npm &>/dev/null; then
  NPM_PREFIX=$(npm config get prefix 2>/dev/null || echo "")
  if [ -n "$NPM_PREFIX" ] && [ -d "${NPM_PREFIX}/lib/node_modules/npm" ]; then
    NPM_DIR="${NPM_PREFIX}/lib/node_modules/npm"
  fi
fi
if [ -n "$NPM_DIR" ] && [ -f "${NPM_DIR}/bin/npm-cli.js" ]; then
  echo "Bundling npm from ${NPM_DIR}"
  tar -C "$(dirname "${NPM_DIR}")" -cf "${ASSET_DIR}/npm.tar" "$(basename "${NPM_DIR}")"
else
  echo "Warning: npm package not found, custom ST installation will not be available in this build"
fi

export ROOT_DIR="${ROOT_DIR}"
export ST_SAFE_DIR="${ST_DIR}"

python3 - <<'PY'
import hashlib
import json
import os
import subprocess
from pathlib import Path

root = Path(os.environ.get("ROOT_DIR", ".")).resolve()
safe_dir = os.environ.get("ST_SAFE_DIR")
st_dir = root / "SillyTavern"
node_dir = root / "node"
asset_dir = root / "app/src/main/assets/node_payload"
bundle = asset_dir / "st_bundle.tar.gz"
if not bundle.exists():
    bundle = asset_dir / "st_bundle.tar"

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def git_rev() -> str:
    try:
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(st_dir),
                "-c",
                f"safe.directory={safe_dir}" if safe_dir else "safe.directory=*",
                "rev-parse",
                "--short=12",
                "HEAD",
            ],
            text=True,
        ).strip()
    except Exception:
        return "unknown"

def st_version() -> str:
    pkg = st_dir / "package.json"
    if not pkg.exists():
        return "unknown"
    with pkg.open("r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("version", "unknown")

def node_version_from_header() -> str:
    try:
        header = node_dir / "src" / "node_version.h"
        if not header.exists():
            return "unknown"
        data = header.read_text(encoding="utf-8")
        def grab(name: str) -> str:
            for line in data.splitlines():
                if line.startswith(f"#define {name} "):
                    return line.split()[-1]
            return ""
        major = grab("NODE_MAJOR_VERSION")
        minor = grab("NODE_MINOR_VERSION")
        patch = grab("NODE_PATCH_VERSION")
        if not (major and minor and patch):
            return "unknown"
        return f"v{major}.{minor}.{patch}"
    except Exception:
        return "unknown"

def node_commit() -> str:
    try:
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(node_dir),
                "-c",
                "safe.directory=*",
                "rev-parse",
                "--short=12",
                "HEAD",
            ],
            text=True,
        ).strip()
    except Exception:
        return "unknown"

def node_tag() -> str:
    try:
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(node_dir),
                "-c",
                "safe.directory=*",
                "describe",
                "--tags",
                "--exact-match",
                "HEAD",
            ],
            text=True,
        ).strip()
    except Exception:
        return ""

payload = {
    "payload_version": f"st-{st_version()}-{git_rev()}",
    "st_commit": git_rev(),
    "st_version": st_version(),
    "node_version": node_version_from_header(),
    "node_commit": node_commit(),
    "node_tag": node_tag(),
    "bundle_sha256": sha256_file(bundle) if bundle.exists() else "missing",
}

(asset_dir / "manifest.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
PY
