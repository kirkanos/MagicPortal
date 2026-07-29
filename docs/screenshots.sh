#!/usr/bin/env bash
#
# Regenerates the README screenshots from a running MagicPortal instance using
# headless Google Chrome (no Node/Playwright toolchain needed).
#
# Usage:   docs/screenshots.sh [BASE_URL]
# Default: BASE_URL = https://mtg.kirkanos.net  (the public demo)
#
# Re-run this whenever the UI changes to keep the images in docs/screenshots/
# up to date.
set -euo pipefail

BASE_URL="${1:-https://mtg.kirkanos.net}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/screenshots"
mkdir -p "$OUT_DIR"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
if [ ! -x "$CHROME" ]; then
  for c in chromium chromium-browser google-chrome google-chrome-stable; do
    command -v "$c" >/dev/null 2>&1 && CHROME="$c" && break
  done
fi

shot() {
  local page="$1" file="$2" out="$OUT_DIR/$2"
  local profile
  profile="$(mktemp -d)"
  echo "→ $page → screenshots/$file"
  rm -f "$out"
  # headless Chrome (=new) does not reliably self-exit after --screenshot, so run
  # it in the background, wait for the file, then stop it.
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=1 --window-size=1440,1000 \
    --virtual-time-budget=15000 \
    --user-data-dir="$profile" \
    --screenshot="$out" \
    "$BASE_URL/$page" >/dev/null 2>&1 &
  local pid=$!
  local i=0
  while [ ! -s "$out" ] && [ "$i" -lt 60 ]; do sleep 0.5; i=$((i + 1)); done
  sleep 1 # let the render finalize before killing
  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" 2>/dev/null || true
  rm -rf "$profile"
}

shot "index.html"     "cards.png"
shot "editions.html"  "editions.png"
shot "reserved.html"  "reserved.png"
shot "dashboard.html" "dashboard.png"

echo "Fertig. Bilder in: $OUT_DIR"
