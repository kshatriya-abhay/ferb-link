#!/usr/bin/env bash
# Publishes dist/ferb-link-<versionName>.apk as GitHub release v<versionName> (`mise run
# companion:publish`). Only you run this: it needs a terminal and asks you to type the version.
# It refuses when the tag exists, the tree has changes, the commit isn't pushed, or the APK isn't
# signed with the key in release-cert.sha256.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
fail() { echo "$*" >&2; exit 1; }
[ -t 0 ] && [ -t 1 ] || fail "Run this yourself, in a terminal."

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' companion/app/build.gradle.kts)
TAG=v$VERSION
APK=dist/ferb-link-$VERSION.apk
[ -f "$APK" ] || fail "No $APK: mise run companion:release first"
(cd dist && sha256sum -c --quiet "$(basename "$APK").sha256") || fail "$APK doesn't match its .sha256"
CERT_FILE=${FERB_RELEASE_CERT_FILE:-$ROOT/release-cert.sha256}
[ -s "$CERT_FILE" ] || fail "No $CERT_FILE"
[ "$(release/cert.sh "$APK")" = "$(tr -d '[:space:]' < "$CERT_FILE")" ] || fail "$APK isn't signed with the key in $CERT_FILE"
[ -z "$(git status --porcelain)" ] || fail "Commit or stash your changes first"
git fetch -q origin
git merge-base --is-ancestor HEAD origin/main || fail "Push $(git rev-parse --short HEAD) to origin/main first"
! gh release view "$TAG" >/dev/null 2>&1 || fail "Release $TAG exists"

echo "Publishing $APK as $TAG at $(git rev-parse --short HEAD) on $(gh repo view --json url -q .url)."
read -rp "Type $VERSION to publish: " ANSWER
[ "$ANSWER" = "$VERSION" ] || fail "Not published."
gh release create "$TAG" "$APK" "$APK.sha256" --target "$(git rev-parse HEAD)" --title "Ferb Link $VERSION" --notes "Ferb Link $VERSION, signed with the same key as Ferb's releases (certificate SHA-256 $(cat "$CERT_FILE")).

Install it with Ferb from [Ferb's releases](https://github.com/kshatriya-abhay/ferb/releases/latest); set up your computer with the [quick start](https://github.com/kshatriya-abhay/ferb-link#quick-start).

SHA-256: $(cut -d' ' -f1 "$APK.sha256")"
