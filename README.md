# ferb-link

Lets [Ferb](https://github.com/kshatriya-abhay/ferb), the offline Android assistant, use bigger models on a laptop over Tailscale. Ferb itself never gets the INTERNET permission. A companion app holds it and passes Ferb's requests to a model server on the laptop. When the laptop can't be reached, Ferb answers on the phone as before.

- `server/`: the laptop's model server. It's llama.cpp's `llama-server` (a pinned release build, the same version as Ferb's bench) running as a systemd user service on localhost. Tailscale Serve puts it on the tailnet.
- `companion/`: **Ferb Link**, the Android companion app. It holds the INTERNET permission and answers Ferb through a content provider ([its README](companion/README.md)).

## Server

Needs [mise](https://mise.jdx.dev), Tailscale, and for the CUDA build an NVIDIA driver for CUDA 13.

```sh
mise run server:install            # llama.cpp b11541, CUDA build (or: server:install vulkan); API key; systemd unit
# set MODEL in server/server.conf to a name from server/models.txt, then:
mise run server:model qwen3.5-2b   # download it (pinned, SHA-256 checked)
mise run server:start              # start it and wait until it answers
mise run server:ping               # one test request with a timer tool
mise run server:tailnet on         # http://<laptop>.<tailnet>.ts.net, for the phone
mise run server:ping tailnet       # the same request through the tailnet
mise run server:key                # the API key, for the companion app
mise run server:chat               # try the model in the terminal: chat, /tools for Ferb's tools, /image, /audio, /record
mise run server:mode full          # all of Gemma 4's inputs: images and audio too (server:mode text to go back)
```

**Capabilities.** `CAPABILITIES=full` is the setting now, because Ferb's voice input needs it (`transcribe` in Ferb Link). `text` is what Ferb's bench measured. `full` adds the model's projector (`server/projectors.txt`; Gemma 4 E2B's has a vision and an audio encoder), so requests can carry `image_url` and `input_audio` parts. It costs about 1.15 GB more GPU memory: 3.0 GB idle, up to 3.45 GB while reading a large image, of the RTX 3050 Ti's 4 GB (a 30 s recording peaks at 3.03 GB). llama.cpp calls its audio input experimental. Text requests are as fast as in `text`.

`server:status`, `server:logs`, `server:stop` and `server:run` (in the foreground) do what they say.

**On demand.** The server process stays up, but frees the model after 5 minutes without a request (`SLEEP_IDLE` in `server/server.conf`) and loads it again on the next one: about 2 s with the model file in the page cache, 10–30 s from disk. Asleep it holds 0.2 GB of GPU memory and the card idles (about 10 W, against 15.6 W with the model loaded). `/health` and `/v1/models` don't wake it.

**Unloading.** llama-server runs as a router (`--models-preset`, written from `server.conf` to `server/build/preset.ini` on each start): the router answers on the port and runs the model in a child process, started by the first request. `mise run server:unload`, or Unload in Ferb Link, stops that process at once and frees all of it (0.1 GB of GPU memory left, against 0.2 GB asleep). The next request starts it again, like a wake. Because of the router, every request names the model (`"model": "gemma-4-e2b"`; without it the router answers 400), and `/props` takes `?model=gemma-4-e2b&autoload=false`.

**One model at a time.** A served model shares the CPU, GPU and memory with anything else running models, so timings from Ferb's bench mean nothing while it runs. Stop the server (`mise run server:stop`) before a bench. `server:start` refuses to start while the bench holds its lock. The service isn't enabled at login for the same reason.

**Why not Docker:** the GPU in a container needs the NVIDIA Container Toolkit and a running Docker daemon, and the CUDA image is several GB. The release build runs directly on the installed driver, its version is pinned with a checksum like the bench's, and systemd handles restarts and logs.

**Access:** the server listens on 127.0.0.1 only, so only Tailscale Serve reaches it from outside, and only from devices on the tailnet. Requests also need the API key (`Authorization: Bearer …`). `/health` answers without it.

**Plain HTTP:** Tailscale encrypts all traffic between devices (WireGuard), so HTTPS would add nothing but the need to turn on the tailnet's certificates. Android blocks plain HTTP by default, so the companion app allows it for `ts.net` names only (a network security config).

## Companion app

Needs the Android SDK in `~/Android/Sdk` (mise sets up Java 21).

```sh
mise run companion:test                      # JVM unit tests
mise run companion:install                   # or: companion:send-over-tailscale <phone>
mise run companion:try "set a timer for 5"   # ask it through adb, as Ferb would (debug builds)
```

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT): the model server.
- [Tailscale](https://tailscale.com): the network between phone and laptop.
- [Jetpack Compose and Material 3](https://developer.android.com/jetpack/compose) (Apache-2.0): the companion's interface. Its icon is Material Symbols' "link" (Apache-2.0).
- Models: see `server/models.txt` for the pinned files and their Hugging Face repositories.
