#!/usr/bin/env bash
# Tests for the server scripts that need no model, GPU or network (`mise run server:test`).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

# run.sh refuses while Ferb's bench holds its lock, whoever starts it (server:start, or systemd at
# login and on restart), and checking never creates the lock file.
export FERB_REPO="$T/ferb"
LOCK="$FERB_REPO/build/bench/.lock"
mkdir -p "$(dirname "$LOCK")"

out=$("$ROOT/server/run.sh" check 2>&1 || true)
check "no lock file: not refused for the bench" '! grep -q "bench is running" <<<"$out"'
check "no lock file: the check doesn't create one" '[ ! -e "$LOCK" ]'

touch "$LOCK"
out=$("$ROOT/server/run.sh" check 2>&1 || true)
check "free lock: not refused for the bench" '! grep -q "bench is running" <<<"$out"'

# Held by this shell, as the bench holds it (`exec 9> …; flock 9`), so nothing outlives the test.
exec 9<"$LOCK"; flock 9
status=0; out=$("$ROOT/server/run.sh" check 2>&1 9<&-) || status=$?
check "held lock: refused" '[ "$status" != 0 ] && grep -q "bench is running" <<<"$out"'
exec 9<&-
check "held lock: released when the test lets go" 'flock -n "$LOCK" true'

# server:mode's choice goes in server.local.conf (gitignored), which server.conf reads last, so
# switching modes never edits the tracked file.
mkdir -p "$T/repo/server"
cp "$ROOT/server/server.conf" "$T/repo/server/"
check "without server.local.conf: the tracked default" '[ "$(bash -c "source $T/repo/server/server.conf; echo \$CAPABILITIES")" = full ]'
echo "CAPABILITIES=text" > "$T/repo/server/server.local.conf"
check "server.local.conf overrides it" '[ "$(bash -c "source $T/repo/server/server.conf; echo \$CAPABILITIES")" = text ]'

[ "$fails" = 0 ] && echo "All server tests passed." || { echo "$fails failed."; exit 1; }
