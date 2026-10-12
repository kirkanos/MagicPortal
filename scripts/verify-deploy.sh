#!/usr/bin/env bash
#
# Checks after a deploy whether the service is really running.
#
# "systemctl start" returns as soon as the start command has been issued - not
# when the containers are up. Without this check the pipeline reports success
# even if an image is missing or a container crashes right away.
#
# Runs on the server; the pipeline pipes it in over ssh:
#
#   ssh server "bash -s -- <service> [seconds]" < scripts/verify-deploy.sh
#
# Success means: the systemd unit is active, all containers are running, and
# any container with a healthcheck reports "healthy" - several times in a row,
# so a container stuck in a restart loop cannot slip through by chance.
set -uo pipefail

SERVICE=${1:?Usage: verify-deploy.sh <service> [seconds]}
TIMEOUT=${2:-300}
INTERVAL=5
STABLE=3

cd "/services/$SERVICE" || exit 1

state() {
  docker compose ps --all --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null
}

report() {
  echo "--- systemd ---" >&2
  systemctl status --no-pager --lines 0 "$SERVICE" >&2 || true
  journalctl -u "$SERVICE" --no-pager -n 20 >&2 || true
  echo "--- containers ---" >&2
  state >&2 || true
  echo "--- last log lines ---" >&2
  docker compose logs --tail 30 2>&1 | tail -60 >&2 || true
}

echo "== Waiting for $SERVICE (up to ${TIMEOUT}s)"
deadline=$((SECONDS + TIMEOUT))
good=0
while :; do
  active=$(systemctl is-active "$SERVICE" 2>/dev/null)
  output=$(state)

  # Containers without a healthcheck of their own report an empty field; for
  # those all that counts is that they are running.
  pending=$(awk -F'|' '$2 != "running" || ($3 != "" && $3 != "healthy") { printf "%s(%s%s) ", $1, $2, ($3 != "" ? "," $3 : "") }' <<< "$output")
  [[ -z $output ]] && pending="(no containers) "
  [[ $active != active ]] && pending="systemd:$active $pending"

  if [[ -z $pending ]]; then
    good=$((good + 1))
    if (( good >= STABLE )); then
      echo "   systemd active, all containers running:"
      tr "|" " " <<< "$output" | sed "s/^/     /"
      echo "== Deploy confirmed"
      exit 0
    fi
  else
    good=0
    echo "   not ready yet: $pending"
  fi

  if (( SECONDS >= deadline )); then
    echo "Timed out after ${TIMEOUT}s. Not ready: $pending" >&2
    report
    exit 1
  fi

  sleep "$INTERVAL"
done
