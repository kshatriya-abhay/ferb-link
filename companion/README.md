# Ferb Link (companion app)

An Android app (`dev.abhay.ferblink`) that holds the INTERNET permission Ferb must never have, and passes Ferb's requests to the laptop's model server over Tailscale. Ferb stays offline. Without Ferb Link, when it's switched off, or when the laptop can't be reached, Ferb answers with Needle 3 on the phone as before.

## Using it
1. On the laptop: `mise run server:start`, `mise run server:tailnet on` (prints the address), `mise run server:key` (prints the key).
2. Install Ferb Link (from Releases, `mise run companion:install`, or `companion:send-over-tailscale <phone>`). Until it's connected it shows only how to connect. Pair it: `mise run companion:pair` (USB) or `mise run server:pair` (a QR code for the camera) opens a `ferblink://pair?url=…&key=…` link, and Ferb Link asks before saving it, showing the computer's name in full (any app can open such a link). Or tap Enter by hand. It checks the connection.
3. The status card shows whether the computer answers, the model, its state (Loaded, Asleep, Unloaded), and a mic when it takes voice. Refresh checks again; Unload stops the model on the computer to free its memory, and the next request starts it again.
4. Try sends a request with three sample tools (timer, alarm, flashlight); tap the answer for the time and the confidence stand-ins.
5. Recent lists what went to the computer, recordings included with what was heard (kept in memory only); tap a row for details.
6. Voice needs the server in full mode (`mise run server:mode full`); Check shows whether it's available.

## For Ferb: `LinkContract`
One `ContentResolver.call` on `dev.abhay.ferblink.link`; there's no service to bind to. See [`LinkContract.kt`](app/src/main/kotlin/dev/abhay/ferblink/LinkContract.kt).
- `status`: no network. Returns `version`, `enabled`, `configured`, `model` (the model the server last named; empty if none yet), and `audio` (whether the server last said it takes audio; refreshed by `check`, `wake` and answers).
- `complete`: takes `request`, `tools` (the JSON array of schemas Needle 3 gets), and optionally `facts` (added to the system prompt) and `timeout_ms` (the answer timeout, 1–60 s; 30 s without it). Returns `reply`, JSON shaped like Needle 3's (`success`, `function_calls`, `confidence`, `error`), plus `first`, `mean`, `text`, `model`, `ms` and `source: "laptop"`. It blocks until the laptop answers (3 s to connect at most), so call it off the main thread. On any failure `success` is false, `error` says why in a few lowercase words for a chat bubble ("timed out", "can't reach your computer", "key refused", "server error 500", "model loading", "bad request: …"), `error_detail` in a sentence, and `error_kind` is one of:
  - `off`, `not_set_up`;
  - `bad_request` (no request, or the tools aren't a JSON array);
  - `unreachable` (no connection), `timeout` (connected, no answer in time);
  - `auth` (the API key was refused), `server` (any other non-200, including 503 while the model loads), `bad_reply` (not the expected JSON).
- `check`: whether the laptop answers now (`/health`, `/v1/models` and `/props`, 2 s each; doesn't wake the model). Returns `version`, `reachable`, `model`, `audio`, and `error` and `error_kind` when it isn't reachable.
- `wake`: returns `version` at once and, in the background, loads the model if it's asleep (about 2 s, once 12 s). Ferb calls it when the user starts recording.
- `transcribe`: takes `audio` (a ByteArray, at most 512 KB and 30 s: Opus in OGG, AAC, or anything Android decodes; WAV is passed on as it is), `audio_mime`, and optionally `timeout_ms` (15 s without it) and `hints` (reserved, ignored). Ferb Link decodes it to 16 kHz mono WAV, the server's format. Returns `reply`: `success`, `heard` (what was said, Hindi in Latin letters), `text` (the same request as a plain English command, equal to `heard` for English speech), `model`, `ms`, `source`, and `error` and `error_kind` as for `complete`. No speech gives empty `heard` and `text`: recordings whose loudness hardly changes (silence, room noise, hum) are answered without the model (`SpeechCheck`), and the model says first whether anyone spoke. Debug builds also take `audio_base64` (a string) for trying it from `adb shell`.
- **Version:** additions (extras, reply fields, error kinds, methods) keep `version` at 1; a changed meaning bumps it. An older Ferb Link throws `IllegalArgumentException` for a method it doesn't have.
- Ferb needs `<queries><package android:name="dev.abhay.ferblink" /></queries>` to see the provider.
- **Who may call:** apps signed with the same key (`PackageManager.checkSignatures`), so Ferb and Ferb Link debug builds from the same machine. Debug builds also accept `adb shell` (`mise run companion:try "set a timer"`).

## How it asks the server
Every request names the model (the server's router needs it): the last one the server named, or the first in `/v1/models`. Like Ferb's bench (`tool-check/bench.py`), so the bench's results hold: the same system prompt, the tools as OpenAI functions, `tool_choice: auto`, temperature 0, 256 tokens at most, thinking off, `logprobs` on. The bench's three stand-ins for a confidence come back: the weakest token (`confidence`), the first token (`first`) and their `mean`. Not ported yet: the bench's fallbacks for models that write calls as text (FunctionGemma's own format, Hammer's list).

Recordings go with their own prompt (`ServerClient.TRANSCRIBE`), at temperature 0, with a JSON schema. Any Devanagari the model writes anyway is converted to Latin letters on the phone (`Latin`, with ICU, as Ferb does for contacts; an ASCII-only schema pattern made llama.cpp fail some answers with HTTP 500). The answer is held to `{speech, hindi, heard, text}` in that order, so the model decides whether anyone spoke before writing words; asked only to leave the fields empty, it turned silence and noise into commands. When nothing was Hindi (by the model, or a list of common Hindi words), `text` is set to `heard`: asked to restate English, the model sometimes changed its meaning.

## Network
Plain HTTP, only to Tailscale names (`*.ts.net`, `res/xml/network_security_config.xml`), because the tailnet already encrypts it; HTTPS anywhere. The app rejects other `http://` addresses (a raw `100.x` address or a short name like `my-laptop` won't do).

## Testing
Four tiers. Most tests check one piece; models are evaluated only when tuning them.

| Tier | What | When | How |
|---|---|---|---|
| Unit | `Amounts`, `SpeechCheck`, `Latin`, `AudioDecoder`'s steps around MediaCodec, request building and reply parsing, error kinds, timeouts and connection reuse (`ServerClient` against a fake llama-server), the provider's calls and caller check (`LinkCalls`). No model, server or phone. | Every change | `mise run companion:test` |
| Contract | `contract/v1/`: one JSON file per case, each method's extras and result, and every error kind. `ContractTest` checks `LinkCalls` gives exactly those; Ferb's tests read a copy (`mise run link:contract-check` / `link:contract-sync` in Ferb). | Every change, in the same run | `mise run companion:test`; after a contract change, `mise run companion:contract-write`, review the diff, tell Ferb |
| Model evaluation | The transcribe prompt and schema, the complete system prompt, tool configuration: the harness, synthetic-voice clip runs, `server:chat`. | Only when tuning a prompt or tool config | `mise run companion:harness`, `mise run server:chat` |
| End to end | `mise run server:ping`, and the ~6-case smoke checklist on the phone (in Ferb's repo). | Before an install-worthy release | by hand |

A miss found in a model evaluation becomes a unit test of the code that should have caught it (most are in `AmountsTest`), not a new evaluation case. Gradle runs without daemons (`--no-daemon`, and the Kotlin compiler in-process): nothing outlives a build to crowd the model server's memory, and never run `./gradlew --stop`, which stops Ferb's builds too.

## Help on the screen
- **Connection errors:** under the status card's error, one line saying what to do (`Guidance`): Tailscale off on the phone (no VPN up), the server not on the tailnet, a refused key (pair again), a loading model (try again), the logs for other server errors.
- **First-use hints** (`Hint`), each shown once until "Got it": the main switch, voice (or how to turn it on), Unload, and Try.
- **Pairing:** a "Connect to your computer" card until an address and key are saved, with Paste link (for camera apps that show a `ferblink://` link without opening it), Enter by hand, and the setup guide.

## Logs
`adb logcat -s FerbLink`: each call's arrival and total time, and each HTTP request's connect, send, wait and read times. Connections are kept and reused (`send()` no longer closes them): the first request after a while takes about 50–100 ms to connect over the tailnet, the next ones 0–2 ms.

## On the laptop, without the phone
`mise run companion:harness in.jsonl [out.jsonl]` runs Ferb Link's own `complete` and `transcribe` code (`LaptopHarness`, a model evaluation: left out of `companion:test`) against the local server: one JSON object per line in, one Ferb Link reply per line out.
- `{"id": "a1", "request": "sava saat baje utha dena", "tools": [...], "facts": "...", "timeout_ms": 10000}` → the `complete` reply, plus `"sent"`: the request after `Amounts.forRequest`, as the model gets it.
- `{"id": "v1", "audio": "clip.ogg"}` → the `transcribe` reply (`heard`, `text`). The file is decoded with ffmpeg to 16 kHz mono WAV, and ICU's transliteration goes through `uconv`; the rest (`SpeechCheck`, the prompt, `Latin`, `Amounts`) is the phone's code.
It starts Gradle without a daemon (about 40 s), so batch the lines. Models run one at a time: don't run it during a bench.
