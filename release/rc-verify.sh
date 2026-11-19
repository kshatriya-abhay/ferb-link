#!/usr/bin/env bash
# Whether APK passed its release-candidate checks: its record RC (rc-check.sh writes it) must be
# for this APK's sha256, name the same file and version, be for the commit it was built from
# (release.sh's .commit beside it), and say pass for the emulator's upgrade, fresh and cross runs
# (cross: with Ferb's last release) and for the phone. publish.sh refuses without it. Ferb's scripts/rc-verify.sh
# reads the same record.
# Usage: release/rc-verify.sh RC APK [VERSION]
set -euo pipefail
RC=${1:?usage: rc-verify.sh RC APK [VERSION]}
APK=${2:?usage: rc-verify.sh RC APK [VERSION]}
fail() { echo "Release candidate: $*" >&2; exit 1; }
[ -f "$RC" ] || fail "no record $RC: run mise run rc:check, then mise run rc:check phone"
[ -f "$APK" ] || fail "no $APK"
field() { sed -n "s/^$1=//p" "$RC" | tail -1; }
[ "$(field sha256)" = "$(sha256sum "$APK" | cut -d' ' -f1)" ] ||
  fail "$RC is for another APK (sha256 $(field sha256)); $APK wasn't checked: run mise run rc:check again"
[ "$(field apk)" = "$(basename "$APK")" ] || fail "$RC names $(field apk), not $(basename "$APK")"
[ -z "${3:-}" ] || [ "$(field version)" = "$3" ] || fail "$RC is for version $(field version), not $3"
BUILT="${APK%.apk}.commit"
[ -f "$BUILT" ] && [ "$(field commit)" = "$(cat "$BUILT")" ] ||
  fail "$RC is for commit $(field commit), but $APK was built from $(cat "$BUILT" 2>/dev/null || echo 'an unknown commit')"
for step in upgrade fresh cross phone; do
  case "$(field $step)" in
    pass|pass\ *) ;;
    "") fail "$RC has no $step result" ;;
    pending*) fail "the phone checklist isn't done: mise run rc:check phone" ;;
    *) fail "$step: $(field $step)" ;;
  esac
done
[ "$(field result)" = pass ] || fail "result is $(field result), not pass"
echo "Release candidate passed: $(basename "$APK"), checked $(field date), phone $(field phone | cut -d' ' -f2)."
