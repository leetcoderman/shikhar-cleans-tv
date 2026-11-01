#!/usr/bin/env bash
# Run after every firmware update on the Haier MatrixTV EE: finds the TV, connects, and asks
# tools/check.py what the update undid (re-enabled packages, Home handed back to the stock launcher).
#
# Before running: on the TV, Settings > System > Developer options > Wireless debugging ON.
# (Pair the computer once from that screen: adb pair <ip>:<port>.)
#
# Usage:  extras/haier-matrixtv-ee/after-firmware-update.sh          report only
#         extras/haier-matrixtv-ee/after-firmware-update.sh --fix    re-disable what came back, launcher back on Home
# Afterwards switch Wireless debugging and USB debugging off again on the TV.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HERE/../.."
ADB="${ADB:-adb}"
PY="$REPO/tools/venv/bin/python"
FIX=""
[ "${1:-}" = --fix ] && FIX="--fix"

# The TV picks a new random address after some reboots, so find it by its Wireless debugging service.
CONNECT=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3; exit}')
if [ -z "$CONNECT" ]; then
  echo "TV not found. Turn on Wireless debugging (Developer options) on the TV and run this again." >&2
  exit 2
fi
TV_HOST="${CONNECT%%:*}"
echo "TV at $TV_HOST"
"$ADB" connect "$CONNECT" >/dev/null
"$ADB" -s "$CONNECT" tcpip 5555 >/dev/null
sleep 5
export TV_HOST

cd "$REPO"
"$PY" -I tools/tv.py 'getprop ro.build.display.id' | sed 's/^/firmware: /'
STATUS=0
for LIST in 1-haier-ads-tracking.txt 2-apps-owner-chose.txt 3-home-takeover.txt; do
  echo "== $LIST"
  "$PY" -I tools/check.py $FIX "$HERE/$LIST" com.gathin.tvhome || STATUS=1
done
[ $STATUS = 0 ] && echo "All three lists are in sync." || echo "Drift found (see above). Run again with --fix to reapply."
exit $STATUS
