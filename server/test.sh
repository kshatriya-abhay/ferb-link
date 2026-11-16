#!/usr/bin/env bash
# Tests for the server scripts that need no model, GPU or network (`mise run server:test`).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

# Models run one at a time, through one lock: Ferb's bench holds build/bench/.lock exclusively
# (`exec 9> …; flock -n 9`), and run.sh holds it shared for the server's whole life, however it
# starts (server:start, server:run, systemd at login and on restart). So each refuses while the
# other runs. Busy is exit 75, which systemd retries; a config error is 1, which it doesn't.
export FERB_REPO="$T/ferb"
LOCK="$FERB_REPO/build/bench/.lock"
bench_could_start() { flock -n "$LOCK" true; }

status=0; out=$(FERB_REPO="$T/none" "$ROOT/server/run.sh" check 2>&1) || status=$?
check "no Ferb repo: not refused for the bench" '[ "$status" != 75 ] && ! grep -q "bench is running" <<<"$out"'
check "no Ferb repo: nothing created" '[ ! -e "$T/none" ]'

mkdir -p "$FERB_REPO"
status=0; out=$("$ROOT/server/run.sh" check 2>&1) || status=$?
check "no lock file: not refused for the bench" '[ "$status" != 75 ] && ! grep -q "bench is running" <<<"$out"'
check "no lock file: run.sh creates it, so the bench sees the server" '[ -f "$LOCK" ]'

# Held by this shell, as the bench holds it, so nothing outlives the test.
exec 9<"$LOCK"; flock 9
status=0; out=$("$ROOT/server/run.sh" check 2>&1 9<&-) || status=$?
check "bench running: refused, exit 75" '[ "$status" = 75 ] && grep -q "bench is running" <<<"$out"'
exec 9<&-
check "bench done: the lock is free" 'bench_could_start'

# The other way round, run.sh past its checks, on a copy of the repo with a stand-in llama-server
# that only waits: while it runs, a bench can't take the lock; once it exits, it can.
F="$T/fake"
mkdir -p "$F/server/build/llama" "$T/models"
cp "$ROOT/server/run.sh" "$ROOT/server/server.conf" "$ROOT/server/models.txt" "$F/server/"
read -r FAKE_MODEL _ _ FAKE_FILE _ < <(grep -v '^#' "$ROOT/server/models.txt" | head -1)
printf 'MODEL=%s\nCAPABILITIES=text\nMODELS_DIR=%s\nKEY_FILE=%s\n' "$FAKE_MODEL" "$T/models" "$T/key" > "$F/server/server.local.conf"
touch "$T/models/$FAKE_FILE"; FAKE_KEY="not-a-real-key-$RANDOM$RANDOM"; echo "$FAKE_KEY" > "$T/key"
printf '#!/usr/bin/env bash\ntouch "%s"\nexec sleep 30\n' "$T/serving" > "$F/server/build/llama/llama-server"
chmod +x "$F/server/build/llama/llama-server"
"$F/server/run.sh" >/dev/null 2>&1 & server=$!
for _ in $(seq 50); do [ -e "$T/serving" ] && break; sleep 0.1; done
check "server running: it holds the lock, so a bench can't start" '[ -e "$T/serving" ] && ! bench_could_start'
kill "$server"; wait "$server" 2>/dev/null || true
check "server stopped: a bench can start" 'bench_could_start'

# A CAPABILITIES other than text or full is refused, not quietly run as text.
echo "CAPABILITIES=ful" >> "$F/server/server.local.conf"
status=0; out=$("$F/server/run.sh" check 2>&1) || status=$?
check "CAPABILITIES=ful: refused, exit 1" '[ "$status" = 1 ] && grep -q "CAPABILITIES" <<<"$out"'
sed -i '$d' "$F/server/server.local.conf"

# server:chat asks about the model without loading it: `/props` with autoload=false, as the app
# does, against a stand-in router whose model is unloaded. What the model takes in then comes from
# CAPABILITIES.
cp "$ROOT/server/chat.sh" "$F/server/"
python3 "$ROOT/server/testdata/stub-server.py" "$T/requests" "$T/port" & stub=$!
for _ in $(seq 50); do [ -s "$T/port" ] && break; sleep 0.1; done
# A curl that notes its arguments, to show the key never is one (any local user can read them).
mkdir -p "$T/bin"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> "%s"\nexec %s "$@"\n' "$T/curl-args" "$(command -v curl)" > "$T/bin/curl"
chmod +x "$T/bin/curl"
out=$(PATH="$T/bin:$PATH" "$F/server/chat.sh" "http://127.0.0.1:$(cat "$T/port")" </dev/null 2>&1 || true)
kill "$stub"; wait "$stub" 2>/dev/null || true
check "server:chat: /props never loads the model" 'grep -q "^/props" "$T/requests" && ! grep "^/props" "$T/requests" | grep -vq "autoload=false"'
check "server:chat: the key is never in curl's arguments or the output" '[ -s "$T/curl-args" ] && ! grep -qF "$FAKE_KEY" "$T/curl-args" && ! grep -qF "$FAKE_KEY" <<<"$out"'
check "no script puts the key in curl's arguments" '! grep -nE "Bearer (\\\$\\(|\\\$KEY)" "$ROOT/mise.toml" "$ROOT"/server/*.sh'
check "server:chat: an unloaded model's inputs come from CAPABILITIES" 'grep -q "taking text\." <<<"$out"'

# server:mode's choice goes in server.local.conf (gitignored), which server.conf reads last, so
# switching modes never edits the tracked file.
mkdir -p "$T/repo/server"
cp "$ROOT/server/server.conf" "$T/repo/server/"
check "without server.local.conf: the tracked default" '[ "$(bash -c "source $T/repo/server/server.conf; echo \$CAPABILITIES")" = full ]'
echo "CAPABILITIES=text" > "$T/repo/server/server.local.conf"
check "server.local.conf overrides it" '[ "$(bash -c "source $T/repo/server/server.conf; echo \$CAPABILITIES")" = text ]'
# Settings change in server.local.conf only: one exported in the shell would reach the mise tasks
# but not the systemd unit. FERB_REPO is the exception (these tests set it).
check "MODELS_DIR in the environment doesn't change it" '[ "$(MODELS_DIR=/elsewhere bash -c "source $T/repo/server/server.conf; echo \$MODELS_DIR")" != /elsewhere ]'

# server:mode writes its choice with local-conf.sh: only that setting's line changes, the user's
# other lines stay, and running it twice gives the same file.
cp "$ROOT/server/local-conf.sh" "$T/repo/server/" 2>/dev/null || true
printf 'MODEL=qwen3.5-2b\nCAPABILITIES=full\n# a note\n' > "$T/repo/server/server.local.conf"
"$T/repo/server/local-conf.sh" CAPABILITIES text >/dev/null 2>&1 || true
"$T/repo/server/local-conf.sh" CAPABILITIES text >/dev/null 2>&1 || true
check "local-conf.sh: replaces the setting once, keeps the rest" '[ "$(cat "$T/repo/server/server.local.conf")" = "$(printf "MODEL=qwen3.5-2b\n# a note\nCAPABILITIES=text")" ]'
rm "$T/repo/server/server.local.conf"
"$T/repo/server/local-conf.sh" CAPABILITIES full >/dev/null 2>&1 || true
check "local-conf.sh: creates the file when missing" '[ "$(cat "$T/repo/server/server.local.conf" 2>/dev/null)" = CAPABILITIES=full ]'

# companion:pair sends the pairing link (it holds the API key) to the phone on adb's stdin, never as
# an argument, which any local user could read. Its own script from mise.toml, with a stub adb and a
# stub pair-link.sh: nothing reaches a phone.
P="$T/pair"
mkdir -p "$P/server" "$P/bin"
printf '#!/usr/bin/env bash\necho "ferblink://pair?url=http%%3A%%2F%%2Flaptop.example.ts.net&key=%s"\n' "$FAKE_KEY" > "$P/server/pair-link.sh"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> "%s/adb-args"\ncat >> "%s/adb-stdin"\n' "$P" "$P" > "$P/bin/adb"
chmod +x "$P/server/pair-link.sh" "$P/bin/adb"
python3 -c 'import sys, tomllib; print(tomllib.load(open(sys.argv[1], "rb"))["tasks"]["companion:pair"]["run"])' "$ROOT/mise.toml" > "$P/pair.sh"
(cd "$P" && PATH="$P/bin:$PATH" bash pair.sh </dev/null >/dev/null 2>&1) || true
check "companion:pair: the key is never in adb's arguments" '[ -s "$P/adb-args" ] && ! grep -qF "$FAKE_KEY" "$P/adb-args"'
check "companion:pair: the link reaches the phone on stdin" 'grep -qF "key=$FAKE_KEY" "$P/adb-stdin"'

[ "$fails" = 0 ] && echo "All server tests passed." || { echo "$fails failed."; exit 1; }
