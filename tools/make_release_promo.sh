#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PICS_DIR="$ROOT_DIR/pics"
OUT_FILE="${1:-$PICS_DIR/ST-android-0.3.0-release-promo.png}"

SCREENSHOT="$PICS_DIR/ST-android-screenshot.png"
ICON_SVG="$PICS_DIR/ST-android-app-icon-original.svg"

for f in "$SCREENSHOT" "$ICON_SVG"; do
  if [[ ! -f "$f" ]]; then
    echo "Missing required file: $f" >&2
    exit 1
  fi
done

if ! command -v magick >/dev/null 2>&1; then
  echo "ImageMagick 'magick' is required." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_FILE")"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

# Canvas and layout
CANVAS_W=2400
CANVAS_H=2018
SHOT_H=1760
SHOT_W=942
SHOT_X=86
SHOT_Y=129
CARD_X=1040
CARD_Y=118
CARD_W=1280
CARD_H=1782

FONT_REG="/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-Regular.otf"
FONT_MED="/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-Medium.otf"
FONT_SEMI="/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-SemiBold.otf"
FONT_XBOLD="/usr/share/fonts/julietaula-montserrat-fonts/Montserrat-ExtraBold.otf"

magick -background none -density 512 "$ICON_SVG" -resize 246x246 "$tmpdir/icon.png"
# Rounded screenshot with soft shadow
magick "$SCREENSHOT" -resize "${SHOT_W}x${SHOT_H}!" "$tmpdir/shot-resized.png"
magick -size "${SHOT_W}x${SHOT_H}" xc:none \
  -fill white -draw "roundrectangle 0,0,$((SHOT_W-1)),$((SHOT_H-1)),46,46" \
  "$tmpdir/shot-mask.png"
magick "$tmpdir/shot-resized.png" "$tmpdir/shot-mask.png" -alpha off -compose CopyOpacity -composite \
  "$tmpdir/shot-rounded.png"
magick -size "$((SHOT_W+22))x$((SHOT_H+22))" xc:none \
  -fill '#FFFFFF0E' -stroke '#D6C3FF80' -strokewidth 2 \
  -draw "roundrectangle 1,1,$((SHOT_W+20)),$((SHOT_H+20)),52,52" \
  "$tmpdir/shot-frame.png"

make_bullet_row() {
  local text="$1"
  local font="$2"
  local size="$3"
  local color="$4"
  local width="$5"
  local height="$6"
  local dot_color="$7"
  local out="$8"
  local text_w=$((width - 58))

  magick -background none -fill "$color" \
    -font "$font" -pointsize "$size" \
    -gravity west -size "${text_w}x${height}" caption:"$text" \
    "$tmpdir/_row_text.png"

  magick -size "${width}x${height}" xc:none \
    -fill "$dot_color" -draw "circle 22,$((height/2)) 28,$((height/2))" \
    "$tmpdir/_row_dot.png"

  magick "$tmpdir/_row_dot.png" "$tmpdir/_row_text.png" -geometry +50+0 -compose over -composite "$out"
}

magick -background none -fill white \
  -font "$FONT_XBOLD" -pointsize 78 \
  -size 980x150 caption:"0.3.0 Update" \
  "$tmpdir/title.png"
make_bullet_row "Install different SillyTavern versions" "$FONT_SEMI" 50 white 980 136 '#C79BFF' "$tmpdir/bullet-1.png"
make_bullet_row "One-line chats export from Termux" "$FONT_SEMI" 50 white 980 136 '#C79BFF' "$tmpdir/bullet-2.png"
make_bullet_row "Full UI refresh" "$FONT_MED" 47 '#F2EEFF' 980 114 '#A77CFF' "$tmpdir/bullet-3.png"
make_bullet_row "Auto-open browser" "$FONT_MED" 47 '#F2EEFF' 980 114 '#A77CFF' "$tmpdir/bullet-4.png"
make_bullet_row "New silly app icon" "$FONT_MED" 47 '#E8E4F7' 980 114 '#9A78F7' "$tmpdir/bullet-5.png"
make_bullet_row "Opt-in app auto-updates" "$FONT_MED" 47 '#E8E4F7' 980 114 '#9A78F7' "$tmpdir/bullet-6.png"

# Background and cards
magick -size "${CANVAS_W}x${CANVAS_H}" radial-gradient:'#2F1C5A-#0C1024' \
  -colorspace sRGB "$tmpdir/base.png"

magick "$tmpdir/base.png" \
  \( -size 760x760 radial-gradient:'#8A63FF44-#00000000' -background none -rotate -14 +repage \) -geometry +100-40 -compose screen -composite \
  \( -size 900x900 radial-gradient:'#4E9DFF20-#00000000' -background none -rotate 8 +repage \) -geometry +1460+1080 -compose screen -composite \
  \( -size 460x460 radial-gradient:'#E3B05725-#00000000' \) -geometry +1790+140 -compose screen -composite \
  "$tmpdir/bg.png"

magick -size "${CARD_W}x${CARD_H}" xc:none \
  -fill '#161A34E6' -draw "roundrectangle 0,0,$((CARD_W-1)),$((CARD_H-1)),54,54" \
  "$tmpdir/right-card.png"

magick -size "${CARD_W}x${CARD_H}" xc:none \
  -fill '#B18CFF18' -stroke '#A77CFF66' -strokewidth 3 \
  -draw "roundrectangle 2,2,$((CARD_W-3)),$((CARD_H-3)),54,54" \
  "$tmpdir/right-card-stroke.png"

magick "$tmpdir/right-card.png" "$tmpdir/right-card-stroke.png" -compose over -composite \
  "$tmpdir/right-card-combined.png"

# Accent divider and icon chip
magick -size 820x6 xc:'#A77CFF' "$tmpdir/divider.png"
magick -size 286x286 xc:none \
  -fill '#0D1227F0' -stroke '#9B77FF88' -strokewidth 3 \
  -draw "roundrectangle 1,1,284,284,44,44" \
  "$tmpdir/icon-chip.png"

# Composite everything
magick "$tmpdir/bg.png" \
  "$tmpdir/right-card-combined.png" -geometry +${CARD_X}+${CARD_Y} -compose over -composite \
  "$tmpdir/shot-frame.png" -geometry +$((SHOT_X-11))+$((SHOT_Y-11)) -compose over -composite \
  "$tmpdir/shot-rounded.png" -geometry +${SHOT_X}+${SHOT_Y} -compose over -composite \
  "$tmpdir/icon-chip.png" -geometry +1940+144 -compose over -composite \
  "$tmpdir/icon.png" -geometry +1960+164 -compose over -composite \
  "$tmpdir/title.png" -geometry +1160+252 -compose over -composite \
  "$tmpdir/divider.png" -geometry +1160+452 -compose over -composite \
  "$tmpdir/bullet-1.png" -geometry +1160+510 -compose over -composite \
  "$tmpdir/bullet-2.png" -geometry +1160+650 -compose over -composite \
  "$tmpdir/bullet-3.png" -geometry +1160+798 -compose over -composite \
  "$tmpdir/bullet-4.png" -geometry +1160+918 -compose over -composite \
  "$tmpdir/bullet-5.png" -geometry +1160+1038 -compose over -composite \
  "$tmpdir/bullet-6.png" -geometry +1160+1158 -compose over -composite \
  "$OUT_FILE"

echo "Generated: $OUT_FILE"
