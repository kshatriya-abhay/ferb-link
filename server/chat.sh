#!/usr/bin/env bash
# Talk to the laptop's model server in the terminal: a chat, or requests with Ferb's tools the way
# Ferb Link sends them, with images and audio when the server runs with CAPABILITIES=full.
# `mise run server:chat` (or server/chat.sh [url]); /help lists the commands.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/server/server.conf"
URL=${1:-http://127.0.0.1:$PORT}
KEY=$(cat "$KEY_FILE" 2>/dev/null) || { echo "No API key in $KEY_FILE: mise run server:install" >&2; exit 1; }

# Ferb's tool schemas, as its bench builds them; a few sample tools when they aren't there.
FERB_TOOLS="$FERB_REPO/app/build/tool-check/all.json"
SAMPLE_TOOLS='[
 {"name": "set_timer", "description": "Start a countdown timer", "parameters": {"type": "object", "properties": {"duration": {"type": "string", "description": "How long, e.g. 5 minutes"}}, "required": ["duration"]}},
 {"name": "set_alarm", "description": "Set an alarm for a time of day", "parameters": {"type": "object", "properties": {"time": {"type": "string", "description": "The time, e.g. 6:30 am"}}, "required": ["time"]}},
 {"name": "set_flashlight", "description": "Turn the flashlight on or off", "parameters": {"type": "object", "properties": {"on": {"type": "boolean"}}, "required": ["on"]}}
]'
# Ferb Link's system prompt (ServerClient.SYSTEM), the same as Ferb's bench.
FERB_SYSTEM="You are Ferb, an assistant on an Android phone. When the user asks for something one of the tools does, call that tool with the arguments in the user's words. If no tool fits, or the user isn't asking for an action, reply in a few words without calling a tool."

DIM=$'\e[2m' BOLD=$'\e[1m' CYAN=$'\e[36m' GREEN=$'\e[32m' RED=$'\e[31m' OFF=$'\e[0m'

curl -fs --max-time 3 "$URL/health" >/dev/null || { echo "The server doesn't answer at $URL. Start it: mise run server:start" >&2; exit 1; }
MODEL=$(curl -fs --max-time 3 -H "Authorization: Bearer $KEY" "$URL/v1/models" | jq -r '.data[0].id // "?"')
# What the server takes in besides text (CAPABILITIES=full loads the projector).
read -r VISION AUDIO < <(curl -fs --max-time 3 -H "Authorization: Bearer $KEY" "$URL/props?model=$MODEL" | jq -r '"\(.modalities.vision // false) \(.modalities.audio // false)"')

# Requests, attachments and the conversation go through files: an image or a recording is too big
# for one command-line argument (Linux allows 128 KB).
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
echo '[]' > "$TMP/messages.json"

mode=chat      # chat: a conversation; tools: each line is one request with tools, as Ferb sends it
tools=ferb     # which tools in tools mode: ferb or sample
think=false    # the model's thinking (Gemma 4 has a built-in reasoning mode)
stats=true
system=""      # chat mode's system prompt
RECORD_SECONDS=5

tools_json() {
  if [ "$tools" = ferb ] && [ -f "$FERB_TOOLS" ]; then cat "$FERB_TOOLS"; else echo "$SAMPLE_TOOLS"; fi
}

help() {
  cat <<EOF
${BOLD}Commands${OFF}
  /chat             a conversation (the default); the model sees everything said so far
  /tools [ferb|sample]
                    each line is one request with tools, as Ferb sends it: Ferb's 15 tools
                    (from $FERB_TOOLS, once Ferb has run tool-check), or 3 sample tools
  /image FILE [text]   send a picture, with a question or without (CAPABILITIES=full)
  /audio FILE [text]   send a recording: wav or mp3, anything else is converted with ffmpeg
  /record [SECONDS] [text]
                    record from the microphone (${RECORD_SECONDS} s unless given), then send it;
                    in /tools mode, say a request ("set a timer for five minutes")
  /think on|off     the model's thinking before it answers (off by default, as in Ferb)
  /system [text]    set (or clear) the chat's system prompt
  /reset            forget the conversation
  /stats on|off     show timings and token counts after each answer
  /help, /quit      (Ctrl+D quits too)
File names can't contain spaces; ~ works.
EOF
}

# Writes the user's message content to $TMP/content.json: [text part] + any attachment parts.
content() { # text [part files...]
  local text=$1; shift
  jq -n --arg text "$text" '[if $text == "" then empty else {type: "text", text: $text} end]' > "$TMP/content.json"
  for part in "$@"; do jq -s '.[0] + [.[1]]' "$TMP/content.json" "$part" > "$TMP/c.json" && mv "$TMP/c.json" "$TMP/content.json"; done
}

# An image as an OpenAI image_url part (a data: URL), in $TMP/part-image.json.
image_part() {
  local file=$1 mime
  mime=$(file --mime-type -b "$file")
  [[ $mime == image/* ]] || { echo "${RED}Not an image: $file ($mime)${OFF}"; return 1; }
  base64 -w0 "$file" > "$TMP/b64"
  jq -n --rawfile b64 "$TMP/b64" --arg mime "$mime" '{type: "image_url", image_url: {url: ("data:" + $mime + ";base64," + $b64)}}' > "$TMP/part-image.json"
}

# A recording as an OpenAI input_audio part (wav or mp3), in $TMP/part-audio.json.
audio_part() {
  local file=$1 format=${1##*.}
  format=${format,,}
  if [ "$format" != wav ] && [ "$format" != mp3 ]; then
    ffmpeg -loglevel error -y -i "$file" -ar 16000 -ac 1 "$TMP/converted.wav" || { echo "${RED}ffmpeg couldn't read $file${OFF}"; return 1; }
    file="$TMP/converted.wav"; format=wav
  fi
  base64 -w0 "$file" > "$TMP/b64"
  jq -n --rawfile b64 "$TMP/b64" --arg format "$format" '{type: "input_audio", input_audio: {data: $b64, format: $format}}' > "$TMP/part-audio.json"
}

# Streams a chat answer: thinking dimmed, then the answer. Adds both turns to the conversation.
chat_turn() {
  local reply="" timings="" line data out r c t thinking=false
  jq --slurpfile content "$TMP/content.json" '. + [{role: "user", content: $content[0]}]' "$TMP/messages.json" > "$TMP/m.json" && mv "$TMP/m.json" "$TMP/messages.json"
  # The sampling Google recommends for Gemma 4 (the model file's general.sampling.*).
  jq -n --slurpfile msgs "$TMP/messages.json" --arg sys "$system" --argjson think "$think" --arg model "$MODEL" '{
    model: $model,
    messages: ((if $sys == "" then [] else [{role: "system", content: $sys}] end) + $msgs[0]),
    stream: true, temperature: 1.0, top_p: 0.95, top_k: 64,
    chat_template_kwargs: {enable_thinking: $think}}' > "$TMP/body.json"
  while IFS= read -r line; do
    case $line in
      data:*) data=${line#data: } ;;
      \{*) echo "${RED}$(jq -r '.error.message // .' <<<"$line")${OFF}"; drop_last_turn; return ;;
      *) continue ;;
    esac
    [ "$data" = "[DONE]" ] && break
    # One jq per chunk: thinking, answer and timings, split by \x01 (the END keeps trailing newlines).
    out=$(jq -j '(.choices[0].delta.reasoning_content // "") + "\u0001" + (.choices[0].delta.content // "") + "\u0001" + (if .timings then (.timings | tostring) else "" end) + "END"' <<<"$data")
    out=${out%END}; r=${out%%$'\x01'*}; out=${out#*$'\x01'}; c=${out%%$'\x01'*}; t=${out#*$'\x01'}
    if [ -n "$r" ]; then $thinking || printf '%s' "$DIM"; thinking=true; printf '%s' "$r"; fi
    if [ -n "$c" ]; then $thinking && { printf '%s\n\n' "$OFF"; thinking=false; }; printf '%s' "$c"; reply+=$c; fi
    [ -n "$t" ] && timings=$t
  done < <(curl -sN -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$URL/v1/chat/completions" --data-binary @"$TMP/body.json")
  printf '%s\n' "$OFF"
  jq --arg text "$reply" '. + [{role: "assistant", content: $text}]' "$TMP/messages.json" > "$TMP/m.json" && mv "$TMP/m.json" "$TMP/messages.json"
  if $stats && [ -n "$timings" ]; then
    jq -r --arg dim "$DIM" --arg off "$OFF" '"\($dim)\(.prompt_n) new prompt tokens in \(.prompt_ms | round) ms, \(.predicted_n) written at \(.predicted_per_second | round) tokens/s\($off)"' <<<"$timings"
  fi
}

drop_last_turn() { jq '.[:-1]' "$TMP/messages.json" > "$TMP/m.json" && mv "$TMP/m.json" "$TMP/messages.json"; }

# One request with tools, as Ferb Link sends it (temperature 0, Ferb's prompt): the calls, the
# words, and the first token's probability (Ferb's bench's "first").
tools_turn() {
  local started ms
  tools_json > "$TMP/tools.json"
  jq -n --slurpfile content "$TMP/content.json" --arg sys "$FERB_SYSTEM" --slurpfile tools "$TMP/tools.json" --argjson think "$think" --arg model "$MODEL" '{
    model: $model,
    messages: [{role: "system", content: $sys}, {role: "user", content: $content[0]}],
    tools: [$tools[0][] | {type: "function", function: .}], tool_choice: "auto",
    temperature: 0, max_tokens: 256, logprobs: true, chat_template_kwargs: {enable_thinking: $think}}' > "$TMP/body.json"
  started=$(date +%s%N)
  curl -s -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$URL/v1/chat/completions" --data-binary @"$TMP/body.json" > "$TMP/reply.json"
  ms=$(( ($(date +%s%N) - started) / 1000000 ))
  if jq -e '.error' "$TMP/reply.json" >/dev/null 2>&1; then echo "${RED}$(jq -r '.error.message' "$TMP/reply.json")${OFF}"; return; fi
  jq -r --arg green "$GREEN" --arg dim "$DIM" --arg off "$OFF" '
    .choices[0].message as $m
    | (if ($m.reasoning_content // "") != "" then "\($dim)\($m.reasoning_content)\($off)\n" else "" end)
    + (if ($m.tool_calls // []) | length > 0
       then ($m.tool_calls | map("\($green)→ \(.function.name)(\(.function.arguments | fromjson? // . | if type == "object" then to_entries | map("\(.key): \(.value | tojson)") | join(", ") else . end))\($off)") | join("\n"))
       else "No tool call: \($m.content // "")" end)' "$TMP/reply.json"
  if $stats; then
    jq -r --arg dim "$DIM" --arg off "$OFF" --arg ms "$ms" '
      "\($dim)\($ms) ms · first token \(.choices[0].logprobs.content[0].logprob // -99 | exp * 1000 | round / 1000) · \(.timings.prompt_n) new prompt tokens (\(.timings.cache_n // 0) cached) · \(.timings.predicted_n) written\($off)"' "$TMP/reply.json"
  fi
}

send() { if [ "$mode" = chat ]; then chat_turn; else tools_turn; fi; }

# /image, /audio, /record: check the server takes it, build the part, send it with the text.
attach() { # kind file text
  local kind=$1 file=${2/#\~/$HOME} text=$3
  local takes=$VISION; [ "$kind" = audio ] && takes=$AUDIO
  [ "$takes" = true ] || { echo "${RED}The server doesn't take ${kind}s: mise run server:mode full${OFF}"; return; }
  [ -f "$file" ] || { echo "${RED}No file $file${OFF}"; return; }
  "${kind}_part" "$file" || return
  content "$text" "$TMP/part-$kind.json"
  send
}

record() { # [seconds] [text]
  local seconds=$RECORD_SECONDS text=$*
  if [[ ${1:-} =~ ^[0-9]+$ ]]; then seconds=$1; shift; text=$*; fi
  [ "$AUDIO" = true ] || { echo "${RED}The server doesn't take audio: mise run server:mode full${OFF}"; return; }
  echo "${BOLD}Recording ${seconds} s… speak now.${OFF}"
  timeout "$seconds" pw-record --rate 16000 --channels 1 --format s16 "$TMP/recording.wav" 2>/dev/null
  [ -s "$TMP/recording.wav" ] || { echo "${RED}Nothing recorded (pw-record)${OFF}"; return; }
  attach audio "$TMP/recording.wav" "$text"
}

caps="text"; [ "$VISION" = true ] && caps+=", images"; [ "$AUDIO" = true ] && caps+=", audio"
echo "${BOLD}${MODEL}${OFF} at $URL, taking ${caps}. /help for commands; /tools to try Ferb's tools."
[ "$VISION" = true ] || echo "${DIM}For images and audio: mise run server:mode full${OFF}"
history -c
while true; do
  prompt="${CYAN}${mode}$([ "$mode" = tools ] && echo ":$tools")$($think && echo "+think")>${OFF} "
  IFS= read -e -r -p "$prompt" input || { echo; break; }
  [ -z "${input// }" ] && continue
  history -s -- "$input"
  read -r cmd arg1 rest <<<"$input"
  case $input in
    /quit|/exit) break ;;
    /help) help ;;
    /chat) mode=chat; echo "Chat: the model sees the whole conversation (/reset forgets it)." ;;
    /tools|/tools\ *)
      tools=${arg1:-ferb}; mode=tools
      [ "$tools" = ferb ] && [ ! -f "$FERB_TOOLS" ] && echo "Ferb's tools aren't built ($FERB_TOOLS): using the sample tools."
      echo "Tools: $(tools_json | jq -r 'map(.name) | join(", ")')" ;;
    /image\ *) attach image "$arg1" "$rest" ;;
    /audio\ *) attach audio "$arg1" "$rest" ;;
    /record|/record\ *) record ${input#/record} ;;
    /think\ on) think=true ;;
    /think\ off) think=false ;;
    /stats\ on) stats=true ;;
    /stats\ off) stats=false ;;
    /system) system=""; echo "No system prompt." ;;
    /system\ *) system=${input#/system }; echo "System prompt set." ;;
    /reset) echo '[]' > "$TMP/messages.json"; echo "Conversation forgotten." ;;
    /*) echo "Unknown command; /help lists them." ;;
    *) content "$input"; send ;;
  esac
done
