#!/usr/bin/env bash
# Checks a release candidate, dist/ferb-link-<version>.apk (the signed APK release.sh made), on an
# emulator, never a phone, and writes its record dist/ferb-link-<version>.rc, which publish.sh
# requires (release/rc-verify.sh). `mise run rc:check`; then, after the checklist on the phone in
# docs/development.md, `mise run rc:check phone` records the user's answer. Ferb's
# scripts/rc-check.sh does the same for Ferb, with the same record, locks and emulator.
#
# Three runs on the test emulator (release/emu.sh), with neither app installed at the start, each ending with Ferb Link alive and no crash in the
# crash buffer for either app:
#   upgrade: the last published release, with a pairing and Jev's key and limit put in through its
#            own screens, then this APK installed over it (install -r), cold-started into each screen;
#   fresh:   this APK alone, cold-started, paired, then cold-started again into each screen (0.2
#            crashed on exactly that second start);
#   cross:   Ferb's last published release beside this APK, calling it.
# Every launchable activity is started, and the provider is called (the shell's call is refused by
# its signature check: the point is that it answers).
#
# Overrides, for trying the script itself: RC_APK (default dist/…), FROM_APK and CROSS_APK (default:
# downloaded from the last GitHub releases), RC_OUT (default dist/…rc).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source server/server.conf
fail() { echo "rc:check: $*" >&2; exit 1; }

NAME=ferb-link
PKG=dev.abhay.ferblink
FERB_PKG=dev.abhay.assistant
REPO=kshatriya-abhay/ferb-link
FERB_REPO_GH=kshatriya-abhay/ferb
VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' companion/app/build.gradle.kts)
RC_APK=${RC_APK:-dist/$NAME-$VERSION.apk}
RC_OUT=${RC_OUT:-dist/$NAME-$VERSION.rc}
CERT_FILE=${FERB_RELEASE_CERT_FILE:-$ROOT/release-cert.sha256}

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; SDK=${SDK/#\~/$HOME}
export ANDROID_HOME=$SDK
IMAGE="system-images;android-36;google_apis;x86_64"
SERIAL=emulator-5554  # release/emu.sh's
UI="$ROOT/release/rc-ui.py"
AAPT2=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/aapt2

# --- rc:check phone: the user's answer to the phone checklist, for the APK the record is for. ---
if [ "${1:-}" = phone ]; then
  [ -t 0 ] && [ -t 1 ] || fail "Run this yourself, in a terminal: it records your answer."
  [ -f "$RC_OUT" ] || fail "No $RC_OUT: run mise run rc:check first"
  SHA=$(sha256sum "$RC_APK" | cut -d' ' -f1)
  [ "$(sed -n 's/^sha256=//p' "$RC_OUT")" = "$SHA" ] || fail "$RC_OUT is for another APK: run mise run rc:check again"
  echo "The phone checklist for $RC_APK (sha256 $SHA) is in docs/development.md, \"Release candidate on the phone\"."
  read -rp 'Type "pass", or "fail" and what went wrong: ' ANSWER
  case "$ANSWER" in
    pass) PHONE="pass $(date -Iseconds)" ;;
    fail*) PHONE="fail $(date -Iseconds) ${ANSWER#fail}" ;;
    *) fail "Nothing recorded: the answer starts with pass or fail." ;;
  esac
  RESULT=fail
  if grep -qx 'upgrade=pass.*' "$RC_OUT" && grep -qx 'fresh=pass.*' "$RC_OUT" && grep -qx 'cross=pass.*' "$RC_OUT" && [ "${PHONE%% *}" = pass ]; then RESULT=pass; fi
  sed -i "s|^phone=.*|phone=$PHONE|; s|^result=.*|result=$RESULT|" "$RC_OUT"
  echo "Recorded: phone=$PHONE, result=$RESULT ($RC_OUT)."
  exit 0
fi

[ -f "$RC_APK" ] || fail "No $RC_APK: mise run companion:release first"
# The record names the version the APK itself says (with RC_APK set, it needn't be the tree's).
VERSION=$("$AAPT2" dump badging "$RC_APK" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")

# One rc:check at a time on the shared emulator (Ferb's too); emu.sh stop waits for this lock.
exec 8>>/tmp/ferb-rc-emulator.lock
flock -n 8 || fail "An rc:check is using the emulator (Ferb's?)."

WORK=$(mktemp -d)
STARTED=
cleanup() {
  # Stopped only if this run started it; one already running for other work stays.
  [ -z "$STARTED" ] || { exec 8>&-; release/emu.sh stop >/dev/null 2>&1 || true; }
  rm -rf "$WORK"
}
trap cleanup EXIT

# The last published releases, checked against their .sha256 and the release certificate.
release_apk() { # repo name: path of the latest release's APK
  local dir="$WORK/$2"; mkdir -p "$dir"
  gh release download -R "$1" --pattern "$2-*.apk" --pattern "$2-*.apk.sha256" -D "$dir" >/dev/null
  local apk; apk=$(ls "$dir"/*.apk | head -1)
  (cd "$dir" && sha256sum -c --quiet "$(basename "$apk").sha256") || fail "$apk doesn't match its .sha256"
  [ "$(release/cert.sh "$apk")" = "$(tr -d '[:space:]' < "$CERT_FILE")" ] || fail "$apk isn't signed with the release key"
  echo "$apk"
}
FROM_TAG=$(gh release view -R "$REPO" --json tagName -q .tagName)
FROM_APK=${FROM_APK:-$(release_apk "$REPO" "$NAME")}
CROSS_TAG=$(gh release view -R "$FERB_REPO_GH" --json tagName -q .tagName)
CROSS_APK=${CROSS_APK:-$(release_apk "$FERB_REPO_GH" ferb)}
[ -n "${RC_APK_UNSIGNED_OK:-}" ] || [ "$(release/cert.sh "$RC_APK")" = "$(tr -d '[:space:]' < "$CERT_FILE")" ] ||
  fail "$RC_APK isn't signed with the release key"

# --- The emulator (release/emu.sh: headless, the bench lock held, refused beside a loaded model). ---
release/emu.sh serial >/dev/null || { release/emu.sh start >/dev/null; STARTED=1; }
[ "$(release/emu.sh serial)" = "$SERIAL" ] || fail "No emulator at $SERIAL"
a() { adb -s "$SERIAL" "$@"; }
[ "$(a shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' | tr -d '\r' | grep -c '^1$' || true)" -ge 1 ] || fail "$SERIAL isn't an emulator: refusing"
a shell input keyevent KEYCODE_WAKEUP; a shell wm dismiss-keyguard >/dev/null 2>&1 || true
# Neither app from an earlier run (this is the emulator: never the phone).
a uninstall "$PKG" >/dev/null 2>&1 || true
a uninstall "$FERB_PKG" >/dev/null 2>&1 || true
EMULATOR="$IMAGE $(a shell getprop ro.build.fingerprint | tr -d '\r') abis=$(a shell getprop ro.product.cpu.abilist | tr -d '\r')"

# --- Steps, each echoing a problem and returning 1 on failure. ---
PAIR_LINK="ferblink://pair?url=http%3A%2F%2Frc-laptop.example.ts.net&key=rc-check-not-a-real-key-0000"
crashed() { a logcat -b crash -d | grep -E "Process: ($PKG|$FERB_PKG)\b" -A3 | head -8; }
alive() { a shell pidof "$1" >/dev/null; }
cold() { # cold start, wait, and check Ferb Link survived
  a shell am force-stop "$PKG"
  a shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 5
  alive "$PKG" || { echo "Ferb Link isn't running 5 s after a cold start"; return 1; }
}
launch_all() { # every launchable activity of the RC
  local act
  for act in $("$AAPT2" dump badging "$RC_APK" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p"); do
    a shell am start -W -n "$PKG/$act" >/dev/null
    sleep 3
    alive "$PKG" || { echo "Ferb Link died after starting $act"; return 1; }
  done
}
provider() {
  a shell content call --uri "content://$PKG.link" --method status >/dev/null 2>&1 || true
  sleep 1
  alive "$PKG" || { echo "Ferb Link died after a provider call"; return 1; }
}
pair() { # through the link and the confirm dialog, as a user pairs
  a shell am start -a android.intent.action.VIEW -d "'$PAIR_LINK'" "$PKG" >/dev/null
  python3 "$UI" "$SERIAL" wait "Connect to this computer?" || { echo "No pairing dialog"; return 1; }
  python3 "$UI" "$SERIAL" tap "Connect" || { echo "No Connect button"; return 1; }
  python3 "$UI" "$SERIAL" wait "Your computer" || { echo "Not paired: no main screen after Connect"; return 1; }
}
jev() { # a fake key and a $2 limit, through Jev's card under Advanced (never tested: that would call TypeSafe)
  python3 "$UI" "$SERIAL" tap "Test requests, Jev" || { echo "No Advanced section"; return 1; }
  # With no key yet, Jev's switch opens the key and limit.
  python3 "$UI" "$SERIAL" tap "Let Ferb use Jev" || { echo "No Jev card"; return 1; }
  python3 "$UI" "$SERIAL" tap "TypeSafe key" || { echo "No key field"; return 1; }
  a shell input text "rc-check-not-a-real-typesafe-key"
  python3 "$UI" "$SERIAL" tap "Spending limit" || { echo "No limit field"; return 1; }
  a shell input keyevent KEYCODE_MOVE_END; for _ in 1 2 3 4 5 6; do a shell input keyevent KEYCODE_DEL; done
  a shell input text 2
  python3 "$UI" "$SERIAL" tap "Save" || { echo "No Save"; return 1; }
  python3 "$UI" "$SERIAL" wait "Key and spending limit" || { echo "Jev's key wasn't saved"; return 1; }
}
# The screens, from a cold start with the saved state: the main screen, Jev's key and limit (under
# Advanced), the licences.
SCREENS=3
screens() {
  local seen=0
  cold || return 1
  python3 "$UI" "$SERIAL" wait "Your computer" && seen=$((seen + 1))
  if python3 "$UI" "$SERIAL" tap "Test requests, Jev" && python3 "$UI" "$SERIAL" tap "Key and spending limit" &&
     python3 "$UI" "$SERIAL" wait "Spending limit"; then
    seen=$((seen + 1)); python3 "$UI" "$SERIAL" tap "Cancel" || true
  fi
  cold || return 1
  if python3 "$UI" "$SERIAL" tap "Open-source licences" && python3 "$UI" "$SERIAL" wait "Back"; then
    seen=$((seen + 1)); python3 "$UI" "$SERIAL" tap "Back" || true
  fi
  alive "$PKG" || { echo "Ferb Link died on its screens ($seen/$SCREENS seen)"; return 1; }
  [ "$seen" = "$SCREENS" ] || { echo "$seen/$SCREENS screens reached"; return 1; }
  echo "$seen/$SCREENS screens"
}
run() { # run STEP: "pass <detail>" or "fail <problem>" (the step's last line), with the crash buffer checked
  local all st=0 out c
  a logcat -b all -c
  all=$("$@" 2>&1) || st=$?
  out=$(printf '%s\n' "$all" | tail -1)
  c=$(crashed)
  if [ -n "$c" ]; then echo "fail crash: $(echo "$c" | tr '\n' ' ' | cut -c1-200)"
  elif [ "$st" = 0 ]; then echo "pass $out"
  else echo "fail $out"; fi
}

upgrade() {
  a install "$FROM_APK" >/dev/null || { echo "Couldn't install $FROM_TAG"; return 1; }
  a shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 3
  pair || return 1
  jev || return 1
  a logcat -b all -c
  a install -r "$RC_APK" >/dev/null || { echo "install -r failed"; return 1; }
  launch_all || return 1
  provider || return 1
  screens
}
fresh() {
  a uninstall "$PKG" >/dev/null 2>&1 || true
  a install "$RC_APK" >/dev/null || { echo "Couldn't install"; return 1; }
  cold || return 1
  python3 "$UI" "$SERIAL" wait "Connect to your computer" || { echo "No connect screen"; return 1; }
  launch_all || return 1
  pair || return 1
  jev || return 1
  provider || return 1
  screens
}
cross() { # Ferb's last release asking this one for its status, on Ferb's Ferb Link page (paired by fresh)
  a install "$CROSS_APK" >/dev/null 2>"$WORK/cross.err" || { echo "Couldn't install Ferb $CROSS_TAG: $(tr '\n' ' ' < "$WORK/cross.err" | cut -c1-120)"; return 1; }
  a shell monkey -p "$FERB_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 8
  # Ferb's path to the page, as Ferb's session gave it (v0.2): onboarding's Skip on a first start,
  # the Settings icon, the "Ferb Link" row.
  if python3 "$UI" "$SERIAL" has "Skip"; then python3 "$UI" "$SERIAL" tap "Skip"; fi
  python3 "$UI" "$SERIAL" tap "Settings" || { echo "No Settings in Ferb"; return 1; }
  python3 "$UI" "$SERIAL" tap "Ferb Link" || { echo "No Ferb Link row in Ferb's settings"; return 1; }
  python3 "$UI" "$SERIAL" gone "Checking Ferb Link" || { echo "Ferb's status call never finished"; return 1; }
  local bad
  for bad in "Ferb Link isn't installed" "Install Ferb and Ferb Link from the same place" "Versions differ" "Ferb Link failed"; do
    if python3 "$UI" "$SERIAL" has "$bad"; then echo "Ferb says: $bad"; return 1; fi
  done
  local ok seen=
  for ok in "Your computer doesn't answer" "Connected" "Not connected" "Turned off"; do
    if python3 "$UI" "$SERIAL" has "$ok"; then seen=$ok; break; fi
  done
  [ -n "$seen" ] || { echo "Ferb's Ferb Link page shows no status Ferb knows"; return 1; }
  a logcat -d -s FerbLink:I | grep -q "from $FERB_PKG" || { echo "No call from Ferb in Ferb Link's log"; return 1; }
  alive "$FERB_PKG" || { echo "Ferb isn't running"; return 1; }
  alive "$PKG" || { echo "Ferb Link isn't running"; return 1; }
  echo "Ferb $CROSS_TAG's Ferb Link page: $seen"
}

echo "Checking $RC_APK on $SERIAL ($EMULATOR)."
UPGRADE=$(run upgrade); echo "upgrade: $UPGRADE"
FRESH=$(run fresh); echo "fresh: $FRESH"
CROSS=$(run cross); echo "cross: $CROSS"
CROSS_SHA=$(sha256sum "$CROSS_APK" | cut -d' ' -f1)
RESULT=fail  # the phone's checklist is still to do

mkdir -p "$(dirname "$RC_OUT")"
cat > "$RC_OUT" <<EOF
name=$NAME
version=$VERSION
apk=$(basename "$RC_APK")
sha256=$(sha256sum "$RC_APK" | cut -d' ' -f1)
commit=$(cat "${RC_APK%.apk}.commit" 2>/dev/null || echo unknown)
date=$(date -Iseconds)
emulator=$EMULATOR
upgrade_from=$FROM_TAG $(sha256sum "$FROM_APK" | cut -d' ' -f1)
upgrade=$UPGRADE
fresh=$FRESH
cross=${CROSS%% *} ferb $CROSS_TAG $CROSS_SHA ${CROSS#* }
phone=pending
result=$RESULT
EOF
echo "Wrote $RC_OUT."
case "$UPGRADE$FRESH$CROSS" in
  *fail*) fail "The emulator runs failed (above); fix them before the phone." ;;
  *) echo "Next: the checklist on the phone (docs/development.md), then mise run rc:check phone." ;;
esac
