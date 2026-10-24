#!/usr/bin/env bash
#
# Connects the phone over Wi-Fi debugging and routes its localhost:8088 to the
# Mac's local stack (docker-compose.local.yml), so the debug app's default base
# URL http://127.0.0.1:8088 just works - without opening anything to the LAN.
#
#   tools/dev-connect.sh 192.168.2.50:41234     # first time / after a port change
#   tools/dev-connect.sh                        # reconnect to the last address
#
# After pairing once ("adb pair <ip>:<pairing-port>"), adb finds the phone by
# itself via mDNS and lists it as "adb-<serial>-<id>._adb-tls-connect._tcp".
# Prefer that name over ip:port: it survives port changes after a reboot, and
# connecting by ip:port as well makes adb list the same phone twice - which
# runs instrumented tests twice in parallel against each other.

set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
STATE="$(dirname "$0")/../.gradle/dev-device"

addr="${1:-}"
if [[ -z $addr && -f $STATE ]]; then
    addr="$(cat "$STATE")"
fi
if [[ -z $addr ]]; then
    echo "Usage: $0 <ip:port>   (see Settings > Developer options > Wireless debugging)" >&2
    echo "Devices found via mDNS:" >&2
    "$ADB" mdns services >&2 || true
    exit 1
fi

case $addr in
    *._adb-tls-connect._tcp) ;;             # mDNS name: adb is already connected
    *) "$ADB" connect "$addr" ;;
esac
mkdir -p "$(dirname "$STATE")" && echo "$addr" > "$STATE"

# adb reverse is per connection and gone after every reconnect - that is why
# this script exists. Forgetting it looks exactly like "backend down".
"$ADB" -s "$addr" reverse tcp:8088 tcp:8088
"$ADB" -s "$addr" reverse --list

if curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8088/api/health | grep -q 204; then
    echo "Local stack is up: http://127.0.0.1:8088"
else
    echo "Warning: local stack not reachable on the Mac. Start it with:" >&2
    echo "  docker compose -f docker-compose.local.yml up -d --build" >&2
fi
