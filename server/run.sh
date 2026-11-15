#!/usr/bin/env bash
# Runs llama-server on the model named in server.conf, in the foreground, as a router: the router
# answers on $PORT and runs the model in a child llama-server, started on the first request and
# stopped by `POST /models/unload` (Ferb Link's Unload button, `mise run server:unload`), which
# frees all its GPU memory and RAM. Requests must name the model ("model": "$MODEL"). The systemd user unit
# (`mise run server:start`) runs this; `mise run server:run` runs it in the terminal.
# `run.sh check` only checks the settings and files.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/server/server.conf"

# Models run one at a time, through Ferb's bench lock: the bench holds it exclusively, and the
# server holds it shared for its whole life (fd 9 passes through exec to llama-server and its model
# process), however it starts. So each refuses while the other runs. Busy exits 75, which the unit
# retries; every other refusal here exits 1, which it doesn't (ferb-llama.service.in).
if [ -d "$FERB_REPO" ]; then
  BENCH_LOCK="$FERB_REPO/build/bench/.lock"
  mkdir -p "$(dirname "$BENCH_LOCK")"
  exec 9>>"$BENCH_LOCK"
  flock -n -s 9 || { echo "Ferb's bench is running; start the server after it finishes." >&2; exit 75; }
fi

[ -n "$MODEL" ] || { echo "Set MODEL in server/server.conf to a name from server/models.txt" >&2; exit 1; }
read -r _ _ _ FILE _ < <(grep "^$MODEL " "$ROOT/server/models.txt") || { echo "No model $MODEL in server/models.txt" >&2; exit 1; }
# MODELS_DIR (the SSD), or where files were before it existed.
model_file() { if [ -f "$MODELS_DIR/$1" ] || [ ! -f "$ROOT/server/build/models/$1" ]; then echo "$MODELS_DIR/$1"; else echo "$ROOT/server/build/models/$1"; fi; }
MODEL_PATH=$(model_file "$FILE")
[ -f "$MODEL_PATH" ] || { echo "$FILE is missing: mise run server:model $MODEL" >&2; exit 1; }
[ -x "$ROOT/server/build/llama/llama-server" ] || { echo "llama-server is missing: mise run server:install" >&2; exit 1; }
[ -s "$KEY_FILE" ] || { echo "No API key in $KEY_FILE: mise run server:install" >&2; exit 1; }
case "$CAPABILITIES" in text|full) ;; *) echo "CAPABILITIES is text or full, not $CAPABILITIES (server/server.conf)" >&2; exit 1 ;; esac
PROJECTOR=
if [ "$CAPABILITIES" = full ]; then
  read -r _ _ _ PFILE _ < <(grep "^$MODEL " "$ROOT/server/projectors.txt") || { echo "No projector for $MODEL in server/projectors.txt" >&2; exit 1; }
  PROJECTOR=$(model_file "$MODEL-$PFILE")
  [ -f "$PROJECTOR" ] || { echo "The projector is missing: mise run server:mode full" >&2; exit 1; }
fi
[ "${1:-}" = check ] && exit 0  # `run.sh check`: only check that everything is in place

# The router's one model, written afresh from server.conf on every start.
PRESET="$ROOT/server/build/preset.ini"
{
  echo "[$MODEL]"
  echo "model = $MODEL_PATH"
  [ -z "$PROJECTOR" ] || echo "mmproj = $PROJECTOR"
} > "$PRESET"

# One request at a time (-np 1), as the bench measures it, and one model (--models-max 1). Thinking
# is off, as in the bench (requests can still turn it on with chat_template_kwargs). The router
# passes the other options on to the model's process; the key stays with the router.
exec "$ROOT/server/build/llama/llama-server" \
  --models-preset "$PRESET" --models-max 1 \
  --host 127.0.0.1 --port "$PORT" --api-key-file "$KEY_FILE" \
  -ngl "$GPU_LAYERS" -c "$CTX" -np 1 -t "$THREADS" \
  --jinja --chat-template-kwargs '{"enable_thinking":false}' \
  --sleep-idle-seconds "$SLEEP_IDLE" --metrics --no-webui
