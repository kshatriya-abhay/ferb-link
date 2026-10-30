# The model server

llama.cpp's `llama-server` running Gemma 4 E2B as a systemd user service (`ferb-llama`), put on the tailnet with Tailscale Serve. Setup is in the [main README](../README.md#quick-start); access and plain HTTP are under [How the server works](../README.md#how-the-server-works).

**Pinned and checked.** llama.cpp is a release build (b11541) and the models are pinned Hugging Face revisions (`server/models.txt`, `server/projectors.txt`), each checked against its SHA-256. These are the same files Ferb's bench measured, so its results hold. Settings are in `server/server.conf`.

**Capabilities.** `CAPABILITIES=full` is the default, because Ferb's voice input needs it (`transcribe` in Ferb Link). `full` adds the model's projector, Gemma 4 E2B's vision and audio encoder, so requests can carry `image_url` and `input_audio` parts. `text` is what Ferb's bench measured. `full` costs about 1.15 GB more GPU memory: 3.0 GB idle, and up to 3.45 GB while reading a large image (a 30 s recording peaks at 3.03 GB). llama.cpp calls its audio input experimental. Text requests are as fast as in `text` mode.

**On demand.** The server process stays up, but frees the model after 5 minutes without a request (`SLEEP_IDLE`) and loads it again on the next one. That takes about 2 s with the files in the page cache, and about 4 s when they have to be read from an SSD (from a spinning disk it took 48 s). Asleep, it holds 0.2 GB of GPU memory and the card idles (about 10 W, against 15.6 W with the model loaded). `/health` and `/v1/models` don't wake it. Ferb asks Ferb Link to wake it when you start recording, so the load hides behind your speech.

**Unloading.** llama-server runs as a router. `--models-preset` is written from `server.conf` to `server/build/preset.ini` on each start. The router answers on the port and runs the model in a child process, started by the first request. `mise run server:unload`, or Unload in Ferb Link, stops that process at once and frees all of it (0.1 GB of GPU memory left, against 0.2 GB asleep). The next request starts it again, like a wake. Because of the router, every request names the model (`"model": "gemma-4-e2b"`; without it the router answers 400), and `/props` takes `?model=gemma-4-e2b&autoload=false`.

**One model at a time.** A served model shares the CPU, GPU and memory with anything else running models, so a benchmark's timings mean nothing while the server runs. If you have Ferb's repo next to this one, `server:start` refuses to start while Ferb's bench holds its lock, and the bench refuses to run while `ferb-llama` is active. Stop the server before a bench.

**Why not Docker.** The GPU in a container needs the NVIDIA Container Toolkit and a running Docker daemon, and the CUDA image is several GB. The release build runs directly on the installed driver, its version is pinned with a checksum, and systemd handles restarts and logs.

**Other models.** `server/models.txt` lists the other models Ferb's bench compared. Any of them can be served: set `MODEL` in `server/server.conf`, run `mise run server:model <name>`, then `server:mode text` (only Gemma 4 E2B has a projector here) and `server:start`. Gemma 4 E2B was right most often in the bench.
