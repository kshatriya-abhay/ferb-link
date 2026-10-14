#!/usr/bin/env bash
# cert.sh app.apk: the SHA-256 of the APK's signing certificate, lowercase hex without colons, as in
# release-cert.sha256. Fails unless the APK verifies and has exactly one signing certificate.
# apksigner names signers by scheme ("Signer #1", or "V3.0 Signer" with minSdk 33), so any line works.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"; SDK=${SDK/#\~/$HOME}
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
CERTS=$("$BUILD_TOOLS/apksigner" verify --print-certs "$1" | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: *//p' | tr -d ':' | tr 'A-F' 'a-f' | sort -u)
[ -n "$CERTS" ] && [ "$(wc -l <<<"$CERTS")" = 1 ] || { echo "$1 needs exactly one signing certificate" >&2; exit 1; }
echo "$CERTS"
