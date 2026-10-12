#!/usr/bin/env bash
# fetch.sh url path sha256: download url to path unless it's already there, and check its SHA-256.
set -euo pipefail
if [ -f "$2" ] && echo "$3  $2" | sha256sum -c --quiet 2>/dev/null; then exit 0; fi
mkdir -p "$(dirname "$2")"
curl -fL --progress-bar -o "$2.part" "$1"
echo "$3  $2.part" | sha256sum -c --quiet
mv "$2.part" "$2"
