#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB_PATH:-adb}"
GENCARDS_PACKAGE="${GENCARDS_PACKAGE:-com.flyme.auto.gencards}"
BRIDGE_PACKAGE="${GENCARDS_BRIDGE_PACKAGE:-com.flyme.auto.gensimulator.bridge}"
BRIDGE_BOOTSTRAP_ACTIVITY="${GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT:-com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity}"
PORT="${GENCARDS_BRIDGE_PORT:-39091}"
EXPECTED_PROTOCOL="${GENCARDS_PROTOCOL_VERSION:-1}"
EXPECTED_CONTRACT="${GENCARDS_CONTRACT_VERSION:-2}"
SERIAL="${ANDROID_SERIAL:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVICES[@]} -ne 1 ]]; then
    echo "Expected exactly one connected device or set ANDROID_SERIAL." >&2
    exit 2
  fi
  SERIAL="${DEVICES[0]}"
fi

package_apk_path() {
  "$ADB" -s "$SERIAL" shell pm path "$1" | sed -n 's/^package://p' | head -1 | tr -d '\r'
}

GENCARDS_REMOTE_APK="$(package_apk_path "$GENCARDS_PACKAGE")"
BRIDGE_REMOTE_APK="$(package_apk_path "$BRIDGE_PACKAGE")"
[[ -n "$GENCARDS_REMOTE_APK" ]] || { echo "GenCards is not installed" >&2; exit 3; }
[[ -n "$BRIDGE_REMOTE_APK" ]] || { echo "GenSimulator Bridge is not installed" >&2; exit 4; }

if command -v "${APKSIGNER_PATH:-apksigner}" >/dev/null 2>&1; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  "$ADB" -s "$SERIAL" pull "$GENCARDS_REMOTE_APK" "$TMP/gencards.apk" >/dev/null
  "$ADB" -s "$SERIAL" pull "$BRIDGE_REMOTE_APK" "$TMP/bridge.apk" >/dev/null
  APKSIGNER_PATH="${APKSIGNER_PATH:-apksigner}" "$ROOT/scripts/verify-signing.sh" "$TMP/gencards.apk" "$TMP/bridge.apk"
else
  echo "apksigner unavailable: host certificate precheck skipped; /health sameSigner remains mandatory" >&2
fi

USER_ID="$("$ADB" -s "$SERIAL" shell am get-current-user | tr -d '\r')"
[[ "$USER_ID" =~ ^[0-9]+$ ]] || { echo "Unable to determine active Android user" >&2; exit 5; }
START_OUTPUT="$("$ADB" -s "$SERIAL" shell am start --user "$USER_ID" -n "$BRIDGE_BOOTSTRAP_ACTIVITY" 2>&1)"
[[ "$START_OUTPUT" != *"Error:"* ]] || { echo "Bridge bootstrap activity failed: $START_OUTPUT" >&2; exit 5; }
"$ADB" -s "$SERIAL" forward "tcp:$PORT" "tcp:39091"

HEALTH=""
for _ in {1..15}; do
  if HEALTH="$(curl --fail --silent --show-error "http://127.0.0.1:$PORT/health" 2>/dev/null)"; then
    break
  fi
  sleep 0.2
done
[[ -n "$HEALTH" ]] || { echo "Bridge health timeout" >&2; exit 5; }

echo "$HEALTH"
HEALTH_JSON="$HEALTH" EXPECTED_PROTOCOL="$EXPECTED_PROTOCOL" EXPECTED_CONTRACT="$EXPECTED_CONTRACT" python3 - <<'PY'
import json, os, sys
health=json.loads(os.environ["HEALTH_JSON"])
checks = {
    "bridge": health.get("bridge") == "ready",
    "genCardsInstalled": health.get("genCardsInstalled") is True,
    "binderConnected": health.get("binderConnected") is True,
    "sameSigner": health.get("sameSigner") is not False,
    "protocolVersion": health.get("protocolVersion") == int(os.environ["EXPECTED_PROTOCOL"]),
    "contractVersion": health.get("contractVersion") == int(os.environ["EXPECTED_CONTRACT"]),
}
failed=[k for k,v in checks.items() if not v]
if failed:
    print("HEALTH_ASSERT_FAILED " + ",".join(failed), file=sys.stderr)
    sys.exit(6)
print("HEALTH_ASSERT_PASS")
PY

REQUEST_ID="$(uuidgen 2>/dev/null || printf '%s' "smoke-$(date +%s)")"
QUERY="${1:-打开主驾座椅加热}"
BODY="$(REQUEST_ID="$REQUEST_ID" QUERY="$QUERY" python3 - <<'PY'
import json, os
print(json.dumps({"protocolVersion":1,"requestId":os.environ["REQUEST_ID"],"query":os.environ["QUERY"]}, ensure_ascii=False))
PY
)"
RESPONSE="$(curl --fail --silent --show-error -H 'Content-Type: application/json' -X POST "http://127.0.0.1:$PORT/v1/create" --data "$BODY")"
echo "$RESPONSE"
RESPONSE_JSON="$RESPONSE" REQUEST_ID="$REQUEST_ID" python3 - <<'PY'
import json, os, sys
response=json.loads(os.environ["RESPONSE_JSON"])
if response.get("requestId") != os.environ["REQUEST_ID"]:
    print("REQUEST_ID_MISMATCH", file=sys.stderr); sys.exit(7)
if response.get("accepted") is not True or response.get("code") != "INGRESS_ACCEPTED":
    print("CREATE_ASSERT_FAILED", file=sys.stderr); sys.exit(8)
print("CREATE_ASSERT_PASS")
PY
