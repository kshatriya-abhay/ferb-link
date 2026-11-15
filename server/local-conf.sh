#!/usr/bin/env bash
# local-conf.sh NAME VALUE: sets NAME=VALUE in server/server.local.conf (gitignored, read last by
# server.conf), replacing only that setting's line and keeping the rest. For `server:mode`.
set -euo pipefail
LOCAL="$(dirname "$0")/server.local.conf"
{ grep -v "^$1=" "$LOCAL" 2>/dev/null || true; echo "$1=$2"; } > "$LOCAL.new"
mv "$LOCAL.new" "$LOCAL"
