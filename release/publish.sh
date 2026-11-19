#!/usr/bin/env bash
# Publishes dist/ferb-link-<versionName>.apk as GitHub release v<versionName> (`mise run
# companion:publish`). Only you run this: it needs a terminal and asks you to type the version.
# It refuses when the tag exists, the tree has changes, the commit isn't pushed, the APK wasn't
# built from this commit (release.sh's .commit), it isn't signed with the key in release-cert.sha256, or
# CHANGELOG.md has no section for the version (its notes: release/changelog-section.sh), or the APK
# has no passing release-candidate record (release/rc-verify.sh, written by mise run rc:check).
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
BUILT=$(cat "dist/ferb-link-$VERSION.commit" 2>/dev/null || true)
[ "$BUILT" = "$(git rev-parse HEAD)" ] ||
  fail "$APK was built from ${BUILT:-an unknown commit}, not HEAD $(git rev-parse HEAD): check out that commit, or run mise run companion:release again"
git fetch -q origin
git merge-base --is-ancestor HEAD origin/main || fail "Push $(git rev-parse --short HEAD) to origin/main first"
! gh release view "$TAG" >/dev/null 2>&1 || fail "Release $TAG exists"
# Only an APK that passed its release-candidate checks (the emulator's and the phone's).
release/rc-verify.sh "dist/ferb-link-$VERSION.rc" "$APK" "$VERSION" || fail "Not published."
# The release notes are CHANGELOG.md's section for this version, with the signing line after it.
NOTES=$(release/changelog-section.sh "$VERSION") || fail "Write the \"## $VERSION\" section in CHANGELOG.md first"

echo "Publishing $APK as $TAG at $(git rev-parse --short HEAD) on $(gh repo view --json url -q .url)."
read -rp "Type $VERSION to publish: " ANSWER
[ "$ANSWER" = "$VERSION" ] || fail "Not published."
# The tag goes on the commit the APK was built from (checked above), wherever GitHub's default branch is.
gh release create "$TAG" "$APK" "$APK.sha256" --target "$BUILT" --title "Ferb Link $VERSION" --notes "$NOTES

---

Ferb Link $VERSION, signed with the same key as Ferb's releases (certificate SHA-256 $(cat "$CERT_FILE")).

Install it with Ferb from [Ferb's releases](https://github.com/kshatriya-abhay/ferb/releases/latest); set up your computer with the [quick start](https://github.com/kshatriya-abhay/ferb-link#quick-start).

SHA-256: $(cut -d' ' -f1 "$APK.sha256")"
