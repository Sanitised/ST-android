#!/usr/bin/env bash
# Export SillyTavern data from standalone installation for import into ST-android.
#
# Usage (one-liner):
#   bash <(curl -sSf https://raw.githubusercontent.com/Sanitised/ST-android/master/tools/export_to_st_android.sh)
#
# Or, if run from inside your SillyTavern folder:
#   bash export_to_st_android.sh
#

set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

die()  { echo -e "\n${RED}Error: $*${NC}" >&2; exit 1; }
info() { echo -e "  ${CYAN}→${NC} $*"; }
ok()   { echo -e "  ${GREEN}✓${NC} $*"; }
warn() { echo -e "  ${YELLOW}!${NC} $*"; }
step() { echo -e "\n${BOLD}$*${NC}"; }

# ── Environment detection ─────────────────────────────────────────────────────
is_termux() {
    [ -n "${TERMUX_VERSION:-}" ] || [ -d "/data/data/com.termux" ]
}

# ── SillyTavern directory detection ──────────────────────────────────────────
is_st_dir() {
    local dir="$1"
    if [ -d "$dir/data" ]; then
        return 0
    fi
    if [ -f "$dir/package.json" ] && grep -q '"sillytavern"' "$dir/package.json" 2>/dev/null; then
        return 0
    fi
    return 1
}

find_st_dir() {
    # 1. Current working directory
    if is_st_dir "."; then
        realpath "."
        return 0
    fi

    # 3. Common install locations
    local candidates=(
        "$HOME/SillyTavern"
        "$HOME/sillytavern"
        "$HOME/st"
        "$HOME/ST"
    )
    for dir in "${candidates[@]}"; do
        if [ -d "$dir" ] && is_st_dir "$dir"; then
            realpath "$dir"
            return 0
        fi
    done

    return 1
}

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}ST-android — SillyTavern data export${NC}"
echo "Creates a backup archive (.tar.gz) that you can import via the ST-android app."
echo ""

# ── Step 1: requirements ──────────────────────────────────────────────────────
step "[1/4] Checking requirements"

for cmd in tar cp; do
    command -v "$cmd" >/dev/null 2>&1 \
        || die "'$cmd' is not available.\n  Install it (e.g. 'pkg install tar') and run this script again."
done
ok "Required tools available"

# ── Step 2: locate SillyTavern ────────────────────────────────────────────────
step "[2/4] Locating SillyTavern"

if ! FOUND_ST_DIR=$(find_st_dir); then
    echo ""
    echo "  Could not find a SillyTavern installation automatically."
    echo ""
    echo "  Run the script from inside your SillyTavern folder:"
    echo ""
    echo "    cd ~/MySillyTavern"
    echo "    bash <(curl -sSf https://raw.githubusercontent.com/Sanitised/ST-android/master/tools/export_to_st_android.sh)"
    echo ""
    exit 1
fi

info "SillyTavern directory: $FOUND_ST_DIR"

HAS_CONFIG=false
HAS_DATA=false
[ -f "$FOUND_ST_DIR/config.yaml" ] && HAS_CONFIG=true
if [ -d "$FOUND_ST_DIR/data" ] && [ -n "$(ls -A "$FOUND_ST_DIR/data" 2>/dev/null)" ]; then
    HAS_DATA=true
fi

if ! $HAS_CONFIG && ! $HAS_DATA; then
    die "SillyTavern directory found, but it has no config.yaml and no data/.\n  Run SillyTavern at least once so it creates your user data, then try again."
fi

$HAS_CONFIG && ok "config.yaml found" || warn "config.yaml not found — it will be skipped"
$HAS_DATA   && ok "data/ directory found" || warn "data/ is empty — it will be skipped"

# ── Step 3: create archive ────────────────────────────────────────────────────
step "[3/4] Building backup archive"

ARCHIVE_NAME="st_backup_$(date +%Y%m%d_%H%M%S).tar.gz"
ARCHIVE_PATH="$HOME/$ARCHIVE_NAME"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/st_backup"
$HAS_CONFIG && cp "$FOUND_ST_DIR/config.yaml" "$WORK_DIR/st_backup/config.yaml"
$HAS_DATA   && cp -r "$FOUND_ST_DIR/data" "$WORK_DIR/st_backup/data"

info "Compressing…"
tar -czf "$ARCHIVE_PATH" -C "$WORK_DIR" st_backup

ARCHIVE_SIZE=$(du -h "$ARCHIVE_PATH" | cut -f1)
ok "Archive created: $ARCHIVE_PATH  ($ARCHIVE_SIZE)"

# ── Step 4: make it accessible ───────────────────────────────────────────────
step "[4/4] Making archive accessible"

if is_termux; then
    DOWNLOADS_DIR="$HOME/storage/downloads"

    if [ ! -d "$DOWNLOADS_DIR" ]; then
        warn "Termux storage access is not set up yet."
        echo ""
        echo "  About to run termux-setup-storage."
        echo "  → A system dialog will pop up — tap Allow."
        echo ""
        read -r -p "  Press Enter to continue…"
        termux-setup-storage
        # Give the OS a moment to mount the shared storage symlink.
        sleep 2
    fi

    if [ -d "$DOWNLOADS_DIR" ]; then
        cp "$ARCHIVE_PATH" "$DOWNLOADS_DIR/$ARCHIVE_NAME"
        ok "Copied to Downloads: $ARCHIVE_NAME"

        echo ""
        echo -e "${GREEN}${BOLD}All done!${NC}"
        echo ""
        echo "  In ST-android:"
        echo "    1. Stop the server if it is running"
        echo "    2. Open Backup → Import Data"
        echo "    3. Select '${ARCHIVE_NAME}' from Downloads"
    else
        warn "Could not access the Downloads folder."
        echo ""
        echo "  The archive is at:  $ARCHIVE_PATH"
        echo "  Copy it to your Android Downloads folder manually, then import it in ST-android."
    fi
else
    ok "Archive saved to: $ARCHIVE_PATH"

    echo ""
    echo -e "${GREEN}${BOLD}All done!${NC}"
    echo ""
    echo "  Transfer '${ARCHIVE_NAME}' to your Android device,"
    echo "  then in ST-android: Backup → Import Data."
fi

echo ""
