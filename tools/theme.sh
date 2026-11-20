#!/usr/bin/env bash
# Push a theme (a folder with config.json and optionally wallpaper.jpg) to the launcher on the TV and
# restart Home so it picks it up. The launcher reads the files from its external files directory and
# overlays config.json on its built-in config.xml; see themes/README.md for the keys.
#
# Usage: TV_HOST=192.168.1.50 tools/theme.sh themes/aurora
#        TV_HOST=192.168.1.50 tools/theme.sh --reset        # remove the overlay, back to config.xml
set -euo pipefail
# Resolve the theme folder against the caller's directory before moving into tools/.
THEME_ARG="${1:-}"
case "$THEME_ARG" in --reset|"") ;; /*) ;; *) THEME_ARG="$PWD/$THEME_ARG" ;; esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 themes/aurora}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
DIR=/sdcard/Android/data/$PKG/files
tv() { "$PY" -I tv.py "$@"; }

if [ "${1:-}" = --reset ]; then
  tv "rm -rf $DIR/config.json $DIR/wallpaper.jpg $DIR/wallpapers $DIR/quotes.txt; am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null; echo reset" | tail -n 1
  exit 0
fi
THEME="${THEME_ARG:?usage: theme.sh <theme folder> | --reset}"
[ -f "$THEME/config.json" ] || { echo "no config.json in $THEME" >&2; exit 1; }
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$THEME/config.json" 2>/dev/null \
  || { echo "$THEME/config.json is not valid JSON" >&2; exit 1; }

tv "mkdir -p $DIR" >/dev/null
tv --push "$THEME/config.json" "$DIR/config.json"
if [ -f "$THEME/wallpaper.jpg" ]; then
  "$PY" -I b64push.py "$THEME/wallpaper.jpg" "$DIR/wallpaper.jpg"
else
  tv "rm -f $DIR/wallpaper.jpg" >/dev/null
fi
# quotes.txt: quotes above the dock instead of the place's words (one per line, "text | source").
if [ -f "$THEME/quotes.txt" ]; then
  "$PY" -I b64push.py "$THEME/quotes.txt" "$DIR/quotes.txt" | tail -n 1
else
  tv "rm -f $DIR/quotes.txt" >/dev/null
fi
# A wallpapers/ folder is a rotation: the launcher shows one picture after another (wallpaper_minutes).
tv "rm -rf $DIR/wallpapers" >/dev/null
if [ -d "$THEME/wallpapers" ]; then
  tv "mkdir -p $DIR/wallpapers" >/dev/null
  for f in "$THEME"/wallpapers/*.jpg "$THEME"/wallpapers/*.jpeg "$THEME"/wallpapers/*.png; do
    [ -f "$f" ] || continue
    "$PY" -I b64push.py "$f" "$DIR/wallpapers/$(basename "$f")" | tail -n 1
  done
fi
tv "am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null; echo applied" | tail -n 1
echo "Theme $(basename "$THEME") is on the TV. Undo with: tools/theme.sh --reset"
