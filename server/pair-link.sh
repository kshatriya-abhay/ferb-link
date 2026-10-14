#!/usr/bin/env bash
# Prints Ferb Link's pairing link, ferblink://pair?url=…&key=…: this computer's tailnet address and
# the API key (companion/…/Pairing.kt reads it). For `server:pair` and `companion:pair`.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/server/server.conf"
[ -s "$KEY_FILE" ] || { echo "No API key in $KEY_FILE: mise run server:install" >&2; exit 1; }
tailscale serve status --json | jq -e --arg p "127.0.0.1:$PORT" 'any(.. | .Proxy? // empty; endswith($p))' >/dev/null ||
  { echo "The server isn't on the tailnet: mise run server:tailnet on" >&2; exit 1; }
URL="http://$(tailscale status --json | jq -r '.Self.DNSName | rtrimstr(".")')"
jq -rn --arg url "$URL" --rawfile key "$KEY_FILE" '"ferblink://pair?url=\($url|@uri)&key=\($key|rtrimstr("\n")|@uri)"'
