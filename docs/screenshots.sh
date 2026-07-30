#!/usr/bin/env bash
#
# Regenerates the README screenshots using a headless-Chrome *Docker container*
# (no Node/Playwright and no host browser needed).
#
# Usage:
#   docs/screenshots.sh                      # shoots the public demo
#   BASE_URL=http://web docs/screenshots.sh  # shoot a local instance …
#   DOCKER_NETWORK=mtg-portal_default docs/screenshots.sh   # … on the compose network
#
# Re-run whenever the UI changes to keep docs/screenshots/ up to date.
set -euo pipefail

BASE_URL="${BASE_URL:-https://mtg.kirkanos.net}"
IMAGE="${CHROME_IMAGE:-zenika/alpine-chrome:latest}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/screenshots"
mkdir -p "$OUT_DIR"

NET_ARGS=()
[ -n "${DOCKER_NETWORK:-}" ] && NET_ARGS=(--network "$DOCKER_NETWORK")

shot() {
  local page="$1" file="$2" name="mtg-shot-$2"
  echo "→ $page → screenshots/$file"
  docker rm -f "$name" >/dev/null 2>&1 || true
  # Run detached so a non-exiting Chrome can't block us; wait for the file, then stop.
  docker run -d --rm --name "$name" "${NET_ARGS[@]}" \
    -v "$OUT_DIR:/out" "$IMAGE" \
    --no-sandbox --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=1 --window-size=1440,1100 \
    --virtual-time-budget=22000 \
    --screenshot="/out/$file" "$BASE_URL/$page" >/dev/null

  local i=0
  while [ ! -s "$OUT_DIR/$file" ] && [ "$i" -lt 60 ]; do sleep 1; i=$((i + 1)); done
  sleep 2 # let the render finalize
  docker stop "$name" >/dev/null 2>&1 || true
}

shot "index.html"     "cards.png"
shot "editions.html"  "editions.png"
shot "reserved.html"  "reserved.png"
shot "dashboard.html" "dashboard.png"

echo "Fertig. Bilder in: $OUT_DIR"
