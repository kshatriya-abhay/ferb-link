#!/usr/bin/env bash
# The test emulator, for when the phone isn't on adb and for rc:check (`mise run emu:start`,
# `mise run emu:stop`). Ferb's scripts/emu.sh is the same, with the same AVD, port and locks, so
# both repos share one emulator. Always address it as `adb -s emulator-5554`, never without -s, so
# the phone and the emulator never mix.
#   emu.sh start   boots it headless (creating the AVD on first use), waits until it has booted,
#                  and prints its serial; with one already running, only prints the serial
#   emu.sh stop    stops it, unless an rc:check is using it
#   emu.sh serial  prints the serial if it's running (exit 1 if not)
# Models run one at a time (CLAUDE.md), and RAM is tight: the emulator holds Ferb's bench lock,
# shared, for its whole life, as the model server does, so the bench refuses while it runs and it
# refuses while the bench runs; and it won't start while the model server has a model loaded
# (mise run server:unload frees it).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/server/server.conf"
fail() { echo "emu: $*" >&2; exit 1; }

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; SDK=${SDK/#\~/$HOME}
# Expanded for the SDK's own tools too (a literal "~/…" in ANDROID_HOME puts their files elsewhere),
# and the AVDs in one known place.
export ANDROID_HOME=$SDK ANDROID_AVD_HOME=$HOME/.android/avd
IMAGE="system-images;android-36;google_apis;x86_64"
AVD=ferb-test
PORT=5554
SERIAL=emulator-$PORT
LOG_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/ferb-emulator
RC_LOCK=/tmp/ferb-rc-emulator.lock

running() {
  adb -s "$SERIAL" get-state >/dev/null 2>&1 &&
    adb -s "$SERIAL" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' 2>/dev/null | tr -d '\r' | grep -qx 1
}

case "${1:-}" in
  serial)
    running && echo "$SERIAL" ;;
  start)
    if running; then echo "$SERIAL"; exit 0; fi
    [ -x "$SDK/emulator/emulator" ] && [ -d "$SDK/${IMAGE//;//}" ] ||
      fail "No emulator or system image: $SDK/cmdline-tools/latest/bin/sdkmanager --install emulator \"$IMAGE\""
    # A llama-server without --models-preset is the router's model process: a model is loaded.
    if pgrep -a llama-server | grep -v -- --models-preset | grep -q .; then
      fail "The model server has a model loaded; the emulator would push the laptop into swap. mise run server:unload, then start it."
    fi
    mkdir -p "$LOG_DIR" "$ANDROID_AVD_HOME"
    if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
      echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" -s create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >"$LOG_DIR/avdmanager.log" 2>&1 ||
        fail "Couldn't create the AVD: $(grep -i error "$LOG_DIR/avdmanager.log" | tail -2 | tr '\n' ' ')"
      "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD" || fail "avdmanager made no AVD $AVD in $ANDROID_AVD_HOME ($LOG_DIR/avdmanager.log)"
    fi
    if [ -d "$FERB_REPO" ]; then
      mkdir -p "$FERB_REPO/build/bench"
      exec 9>>"$FERB_REPO/build/bench/.lock"
      flock -n -s 9 || fail "Ferb's bench is running; start the emulator after it finishes."
    fi
    # Detached, so it outlives this task; it keeps fd 9 (the bench lock) until it exits, but not fd 8
    # (rc:check's lock, when rc:check starts it), or emu.sh stop would think an rc:check still runs.
    setsid nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim \
      -no-snapshot-save -gpu swiftshader_indirect -memory 2048 >"$LOG_DIR/emulator.log" 2>&1 < /dev/null 8>&- &
    EMU=$!
    exec 9>&-
    died() { fail "The emulator exited: $(grep -E "ERROR|FATAL|PANIC" "$LOG_DIR/emulator.log" | tail -3 | tr '\n' ' ')(log: $LOG_DIR/emulator.log)"; }
    # Booted when sys.boot_completed is 1; given up after 6 minutes, or as soon as the emulator exits.
    for _ in $(seq 180); do
      kill -0 "$EMU" 2>/dev/null || died
      [ "$(timeout 5 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
      sleep 2
    done
    kill -0 "$EMU" 2>/dev/null || died
    running && [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] ||
      fail "It didn't boot in 6 minutes: $LOG_DIR/emulator.log"
    # Ready for taps: awake, unlocked, no animations.
    adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
    adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
      adb -s "$SERIAL" shell settings put global "$s" 0
    done
    echo "$SERIAL" ;;
  stop)
    running || { echo "Not running."; exit 0; }
    exec 8>>"$RC_LOCK"
    flock -n 8 || fail "An rc:check is using it; it stops it when it's done."
    adb -s "$SERIAL" emu kill >/dev/null
    for _ in $(seq 30); do running || { echo "Stopped."; exit 0; }; sleep 1; done
    fail "It didn't stop in 30 s" ;;
  *) echo "usage: emu.sh start|stop|serial" >&2; exit 2 ;;
esac
