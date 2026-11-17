#!/usr/bin/env bash
# Writes a new random API key to FILE, readable only by you (server:install, server:key rotate).
# Written beside FILE and moved over it, so the server never reads a half-written key. Prints nothing.
# Usage: server/new-key.sh FILE
set -euo pipefail
FILE=${1:?usage: new-key.sh FILE}
mkdir -p "$(dirname "$FILE")"
umask 077
TMP=$(mktemp "$FILE.XXXXXX")
head -c 24 /dev/urandom | base64 | tr '+/' '-_' > "$TMP"
mv -f "$TMP" "$FILE"
