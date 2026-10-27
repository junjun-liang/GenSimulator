#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <gencards.apk> <bridge.apk>" >&2
  exit 2
fi

GENCARDS_APK="$1"
BRIDGE_APK="$2"
APKSIGNER="${APKSIGNER_PATH:-${APKSIGNER:-apksigner}}"

if ! command -v "$APKSIGNER" >/dev/null 2>&1 && [[ ! -x "$APKSIGNER" ]]; then
  echo "apksigner not found; set APKSIGNER_PATH" >&2
  exit 3
fi

cert_digest() {
  "$APKSIGNER" verify --print-certs "$1" \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}'
}

GENCARDS_DIGEST="$(cert_digest "$GENCARDS_APK")"
BRIDGE_DIGEST="$(cert_digest "$BRIDGE_APK")"

if [[ -z "$GENCARDS_DIGEST" || -z "$BRIDGE_DIGEST" ]]; then
  echo "Unable to read signer SHA-256 digest" >&2
  exit 4
fi

if [[ "$GENCARDS_DIGEST" == "$BRIDGE_DIGEST" ]]; then
  echo "SIGNATURE_MATCH"
  exit 0
fi

echo "SIGNATURE_MISMATCH"
exit 1
