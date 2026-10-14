#!/usr/bin/env bash
# Builds Ferb Link's release APK and signs it with the release key, on this computer (`mise run
# companion:release`). Ferb's `mise run release` signs the same way with the same key: Ferb Link
# answers only apps signed with its own key.
#
# The key and its passwords come from the environment; the alias and store password are asked for if unset. They
# reach apksigner through the environment only, never a command line, a Gradle property or a file:
#   FERB_RELEASE_KEYSTORE        default ~/.android/keystores/release-key-abhay.jks
#   FERB_RELEASE_KEY_ALIAS
#   FERB_RELEASE_STORE_PASSWORD
#   FERB_RELEASE_KEY_PASSWORD    only if it differs from the store password (never asked for)
#   FERB_RELEASE_CERT_FILE       default release-cert.sha256 (tests point it elsewhere)
#
# The signed APK's certificate must match release-cert.sha256 (its SHA-256, public, the same line in
# Ferb's repo). Without that file, the first run writes it and stops, to be compared and committed.
# Out: dist/ferb-link-<versionName>.apk and its .sha256. Publishing is separate (publish.sh).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
fail() { echo "$*" >&2; exit 1; }

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}; ANDROID_HOME=${ANDROID_HOME/#\~/$HOME}
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
[ -n "$BUILD_TOOLS" ] || fail "No Android build-tools in $ANDROID_HOME"
export FERB_RELEASE_KEYSTORE=${FERB_RELEASE_KEYSTORE:-$HOME/.android/keystores/release-key-abhay.jks}
CERT_FILE=${FERB_RELEASE_CERT_FILE:-$ROOT/release-cert.sha256}
[ -f "$FERB_RELEASE_KEYSTORE" ] || fail "No keystore at $FERB_RELEASE_KEYSTORE (set FERB_RELEASE_KEYSTORE)"

# ask VAR prompt [secret]: VAR from the environment, or typed in.
ask() {
  [ -n "${!1:-}" ] && return
  [ -t 0 ] || fail "Set $1, or run this in a terminal"
  if [ "${3:-}" = secret ]; then read -rsp "$2" "$1"; echo; else read -rp "$2" "$1"; fi
  export "${1?}"
}
ask FERB_RELEASE_KEY_ALIAS "Key alias: "
ask FERB_RELEASE_STORE_PASSWORD "Keystore password: " secret
# Not asked for: in a PKCS12 keystore the key's password is the store's. Set it only when different.
export FERB_RELEASE_KEY_PASSWORD=${FERB_RELEASE_KEY_PASSWORD:-$FERB_RELEASE_STORE_PASSWORD}

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' companion/app/build.gradle.kts)
[ -n "$VERSION" ] || fail "No versionName in companion/app/build.gradle.kts"

# Unsigned: Gradle has no signingConfig, so it never sees the key.
(cd companion && ./gradlew -q --no-daemon :app:assembleRelease)
UNSIGNED=companion/app/build/outputs/apk/release/app-release-unsigned.apk
[ -f "$UNSIGNED" ] || fail "No $UNSIGNED"

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$UNSIGNED" "$TMP/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$FERB_RELEASE_KEYSTORE" --ks-key-alias "$FERB_RELEASE_KEY_ALIAS" \
  --ks-pass env:FERB_RELEASE_STORE_PASSWORD --key-pass env:FERB_RELEASE_KEY_PASSWORD \
  --out "$TMP/signed.apk" "$TMP/aligned.apk"

DIGEST=$(release/cert.sh "$TMP/signed.apk")
if [ ! -s "$CERT_FILE" ]; then
  echo "$DIGEST" > "$CERT_FILE"
  fail "First release: wrote the key's certificate SHA-256 to $CERT_FILE:
  $DIGEST
Check that Ferb's release-cert.sha256 holds the same line, commit the file, and run this again."
fi
[ "$DIGEST" = "$(tr -d '[:space:]' < "$CERT_FILE")" ] ||
  fail "Signed with a different key: $DIGEST, but $CERT_FILE says $(cat "$CERT_FILE")"

# A release must not be debuggable (debug builds also answer adb shell).
if "$BUILD_TOOLS/aapt2" dump badging "$TMP/signed.apk" | grep -q application-debuggable; then fail "The APK is debuggable"; fi

mkdir -p dist
APK=ferb-link-$VERSION.apk
cp "$TMP/signed.apk" "dist/$APK"
(cd dist && sha256sum "$APK" > "$APK.sha256")
echo "dist/$APK, signed by $DIGEST"
cat "dist/$APK.sha256"
echo "To publish it: mise run companion:publish (you run it; it asks first)."
