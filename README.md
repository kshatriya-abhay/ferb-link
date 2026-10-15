# Ferb Link

[Ferb](https://github.com/kshatriya-abhay/ferb) is a private Android assistant that runs a small model on the phone and has no internet permission. Ferb Link lets it use a bigger model on your own computer, without giving Ferb that permission:

- **A model server on your computer** (`server/`): llama.cpp's `llama-server` running Gemma 4 E2B, reachable only from your own devices over [Tailscale](https://tailscale.com), with an API key.
- **Ferb Link, an Android app** (`companion/`): it holds the internet permission and passes Ferb's requests, typed or spoken, to that server. Ferb talks to it on the phone and stays offline itself.

When the computer is off, asleep or unreachable, Ferb answers on the phone as before.

```
Ferb ──(on the phone)──▶ Ferb Link ──(Tailscale)──▶ llama-server + Gemma 4 E2B on your computer
```

## What you need

- **A Linux computer** with an NVIDIA GPU with at least 4 GB of memory (the CUDA build), or another GPU that runs Vulkan (the Vulkan build). Gemma 4 E2B with its audio and image encoder takes about 3 GB of GPU memory and 4 GB of disk.
- **[Tailscale](https://tailscale.com)** on the computer and the phone, on the same tailnet, with MagicDNS on (the default).
- **[mise](https://mise.jdx.dev)**, plus `curl` and `jq`. mise installs Java 21 for the app's build.
- **The Android SDK** in `~/Android/Sdk` (Android Studio installs it there), with its licences accepted (`sdkmanager --licenses`) so Gradle can fetch the platform it needs, and `adb`.
- **Ferb and Ferb Link signed with the same key.** Ferb Link answers only apps signed with its own key: install both from GitHub Releases (one release key signs both), or build both on one computer (its debug key signs both). Don't mix the two.
- **An Android phone** (Android 13 or later) with USB debugging on.

## Quick start

1. **Clone this repo next to Ferb's**, so the two share a parent folder, and trust its mise config:
   ```sh
   git clone https://github.com/kshatriya-abhay/ferb
   git clone https://github.com/kshatriya-abhay/ferb-link
   cd ferb-link
   mise trust && mise install
   ```
2. **Let your user run Tailscale Serve** (once, so the next steps need no sudo):
   ```sh
   sudo tailscale set --operator=$USER
   ```
3. **Install the server.** This downloads llama.cpp's pinned build, creates an API key in `~/.config/ferb-link/api-key`, and installs a systemd user service, `ferb-llama`, that starts at login:
   ```sh
   mise run server:install          # NVIDIA: the CUDA build, with its own CUDA runtime (570 MB download)
   mise run server:install vulkan   # any other GPU
   ```
4. **Download the model**, Gemma 4 E2B and its audio and image encoder (4.1 GB, checksummed):
   ```sh
   mise run server:model gemma-4-e2b
   ```
5. **Start the server and test it:**
   ```sh
   mise run server:start   # "Serving gemma-4-e2b on 127.0.0.1:8080."
   mise run server:ping    # a set_timer call with {"minutes": 5}
   ```
6. **Put it on your tailnet, and test it from there:**
   ```sh
   mise run server:tailnet on     # prints the address, http://<computer>.<tailnet>.ts.net
   mise run server:ping tailnet
   ```
7. **Install Ferb Link on the phone.** Download the latest `FerbLink-*.apk` from this repo's [Releases](https://github.com/kshatriya-abhay/ferb-link/releases/latest) on the phone and open it (allow installing from your browser when asked). Or build it from source, with the phone connected over USB:
   ```sh
   mise run companion:install
   ```
8. **Connect Ferb Link to the computer.** Open Ferb Link: it shows how to connect. With the phone still on USB:
   ```sh
   mise run companion:pair
   ```
   Or show a QR code and scan it with the phone's camera (needs `qrencode`):
   ```sh
   mise run server:pair
   ```
   If the camera only shows the link instead of opening it, copy it and tap **Paste link** in Ferb Link. Either way, Ferb Link asks first, with the computer's name: tap **Connect**. The status card turns green and shows the model. (**Enter by hand** takes the address from step 6 and the key from `mise run server:key`.)
9. **Install Ferb the same way you installed Ferb Link**: from [Ferb's Releases](https://github.com/kshatriya-abhay/ferb/releases/latest), or built on this computer (its README, "Build and run"). In Ferb, open **Settings → Ferb Link** and choose **Choose in the chat**, then pick **Computer** under the chat box and ask something. The answer has a laptop icon: it came from your computer.

## Troubleshooting

| Problem | What to do |
|---|---|
| `server:start` says something is missing | It names the step: `server:install` (llama-server or the key) or `server:model gemma-4-e2b` (the model or its encoder). |
| The server doesn't come up | `mise run server:logs`. With the CUDA build, check `nvidia-smi` works; otherwise try `mise run server:install vulkan`. Out of GPU memory: set `GPU_LAYERS` lower in `server/server.conf`, or `auto`. |
| `server:tailnet on` says access denied | Step 2: `sudo tailscale set --operator=$USER`. |
| The camera doesn't open Ferb Link from the QR code | Some camera apps don't open `ferblink://` links: copy the link it shows and tap **Paste link**, or use `mise run companion:pair` over USB. |
| Ferb Link: "can't reach your computer" | The phone's Tailscale is connected? `mise run server:tailnet` says it's on? The address is the full `….ts.net` name with `http://`. Ferb Link takes plain HTTP only to Tailscale names, not a `100.x` address. |
| Ferb Link: "key refused" | Pair again (`mise run companion:pair` or `server:pair`), or enter the key from `mise run server:key`. |
| "This pairing link doesn't work" | The server isn't on the tailnet (`mise run server:tailnet on`), or the link was cut off: run `server:pair` again. |
| The first answer after a while takes seconds | The server unloads the model after 5 minutes idle and loads it again on the next request: about 2 s, up to 30 s after a reboot. Ferb waits 10 s, then answers on the phone. |
| Ferb doesn't see Ferb Link, or it says it's not allowed | The two apps are signed with different keys: one from Releases and one built here, or built on different computers. Uninstall one and install it the same way as the other. |
| Ferb has no mic button | Voice needs the server in full mode: `mise run server:mode` should say `full` (the default). |
| `server:start` says Ferb's bench is running | Models run one at a time: wait for the bench to finish. |

## Everyday commands

```sh
mise run server:status     # the service, whether it answers, and Tailscale Serve
mise run server:logs       # follow the server's log
mise run server:stop       # stop it (server:start again later; it also starts at login)
mise run server:unload     # free the GPU now; the next request loads the model again
mise run server:mode text  # text only, about 1 GB less GPU memory (server:mode full to go back)
mise run server:chat       # try the model in the terminal: chat, /tools for Ferb's tools, /image, /audio, /record
mise run server:key        # the API key, for Ferb Link

mise run companion:test                      # unit and contract tests (companion/README.md, Testing)
mise run companion:send-over-tailscale <phone>   # install over Taildrop instead of USB
mise run companion:try "set a timer for 5"   # ask Ferb Link through adb, as Ferb would (debug builds)
```

Ferb Link's screens, its contract with Ferb, and its tests are in [companion/README.md](companion/README.md).

## How the server works

**Pinned and checked.** llama.cpp is a release build (b11541) and the models are pinned Hugging Face revisions (`server/models.txt`, `server/projectors.txt`), each checked against its SHA-256. These are the same files Ferb's bench measured, so its results hold. Settings are in `server/server.conf`.

**Capabilities.** `CAPABILITIES=full` is the default, because Ferb's voice input needs it (`transcribe` in Ferb Link). `full` adds the model's projector, Gemma 4 E2B's vision and audio encoder, so requests can carry `image_url` and `input_audio` parts. `text` is what Ferb's bench measured. `full` costs about 1.15 GB more GPU memory: 3.0 GB idle, and up to 3.45 GB while reading a large image (a 30 s recording peaks at 3.03 GB). llama.cpp calls its audio input experimental. Text requests are as fast as in `text` mode.

**On demand.** The server process stays up, but frees the model after 5 minutes without a request (`SLEEP_IDLE`) and loads it again on the next one. That takes about 2 s with the model file in the page cache and 10–30 s from disk. Asleep, it holds 0.2 GB of GPU memory and the card idles (about 10 W, against 15.6 W with the model loaded). `/health` and `/v1/models` don't wake it. Ferb asks Ferb Link to wake it when you start recording, so the load hides behind your speech.

**Unloading.** llama-server runs as a router. `--models-preset` is written from `server.conf` to `server/build/preset.ini` on each start. The router answers on the port and runs the model in a child process, started by the first request. `mise run server:unload`, or Unload in Ferb Link, stops that process at once and frees all of it (0.1 GB of GPU memory left, against 0.2 GB asleep). The next request starts it again, like a wake. Because of the router, every request names the model (`"model": "gemma-4-e2b"`; without it the router answers 400), and `/props` takes `?model=gemma-4-e2b&autoload=false`.

**One model at a time.** A served model shares the CPU, GPU and memory with anything else running models, so a benchmark's timings mean nothing while the server runs. If you have Ferb's repo next to this one, `server:start` refuses to start while Ferb's bench holds its lock, and the bench refuses to run while `ferb-llama` is active. Stop the server before a bench.

**Access.** The server listens on 127.0.0.1 only, so it's reachable from outside only through Tailscale Serve, and only from devices on your tailnet. Requests also need the API key (`Authorization: Bearer …`). `/health` answers without it.

**Plain HTTP.** Tailscale encrypts all traffic between devices (WireGuard), so HTTPS would add only the need to turn on the tailnet's certificates. Android blocks plain HTTP by default, so Ferb Link allows it for `ts.net` names only (a network security config).

**Why not Docker.** The GPU in a container needs the NVIDIA Container Toolkit and a running Docker daemon, and the CUDA image is several GB. The release build runs directly on the installed driver, its version is pinned with a checksum, and systemd handles restarts and logs.

**Other models.** `server/models.txt` lists the other models Ferb's bench compared. Any of them can be served: set `MODEL` in `server/server.conf`, run `mise run server:model <name>`, then `server:mode text` (only Gemma 4 E2B has a projector here) and `server:start`. Gemma 4 E2B was right most often in the bench.

## Releases

Releases are built and signed on the maintainer's computer, not in CI, with the same key as Ferb's releases:

```sh
mise run companion:release   # build, sign, check the certificate: dist/ferb-link-<version>.apk and .sha256
mise run companion:publish   # the maintainer only: asks for the version, then creates GitHub release v<version>
```

`companion:release` asks for the key alias and keystore password unless `FERB_RELEASE_KEY_ALIAS` and `FERB_RELEASE_STORE_PASSWORD` are set (`FERB_RELEASE_KEYSTORE` names the keystore; `FERB_RELEASE_KEY_PASSWORD` only if the key's password differs). They reach `apksigner` through the environment only. Gradle never sees the key. The signed APK's certificate must match `release-cert.sha256`, the same line as in Ferb's repo; to check a downloaded APK yourself, compare `apksigner verify --print-certs` with that file. `companion:publish` refuses without a terminal, with uncommitted or unpushed changes, when the tag exists, or when the APK's certificate doesn't match.

**Who can call Ferb Link.** Ferb Link answers any app signed with its key. For releases, that's Ferb, and also any other app the maintainer signs with the same release key. That's by design (it's how Android's signature checks work), but it means trusting those apps as much as Ferb.

## Credits

| Project | Licence | Used for |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | The model server (downloaded by `server:install`, not included) |
| [Gemma 4 E2B](https://huggingface.co/google/gemma-4-E2B-it) by Google, GGUF and projector by [Unsloth](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF) | Apache-2.0 | The default model, with its vision and audio encoder (downloaded by `server:model`) |
| [FunctionGemma 270M](https://huggingface.co/google/functiongemma-270m-it) by Google (GGUF by Unsloth) | Gemma terms | Optional model in `server/models.txt` |
| [LFM2.5-350M, LFM2.5-1.2B](https://huggingface.co/LiquidAI) by Liquid AI | LFM Open License | Optional models |
| [Qwen3.5-0.8B, Qwen3.5-2B](https://huggingface.co/Qwen) by Alibaba (GGUF by Unsloth) | Apache-2.0 | Optional models |
| [Hammer2.1-0.5b](https://huggingface.co/MadeAgents/Hammer2.1-0.5b) by MadeAgents (GGUF by mradermacher) | CC-BY-NC-4.0 | Optional model |
| [Jev](https://docs.typesafe.ai) by TypeSafe AI | Cloud API (TypeSafe's terms), no weights | Being evaluated as a second opinion on Ferb's tool choice (`companion:harness`'s `decide`); off and unused in the app |
| [qrencode](https://fukuchi.org/works/qrencode/) | LGPL-2.1 (library), GPL (tool) | `server:pair`'s QR code (optional, installed separately) |
| [Tailscale](https://tailscale.com) | BSD-3-Clause (client) | The network between phone and computer (installed separately) |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Compose, Material 3, Lifecycle, Core) | Apache-2.0 | Ferb Link's interface |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | The Material 3 window theme behind Compose |
| [Material Icons and Symbols](https://github.com/google/material-design-icons) | Apache-2.0 | Icons, including the app icon ("link") and the mic |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | Background work |
| [JUnit 4](https://junit.org/junit4/), [Truth](https://github.com/google/truth), [JSON-java](https://github.com/stleary/JSON-java) | EPL-1.0, Apache-2.0, Public domain | Tests only (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). The models and llama.cpp are downloaded under their own licences, listed above.
