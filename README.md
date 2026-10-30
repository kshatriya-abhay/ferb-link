# Ferb Link

[![CI](https://github.com/kshatriya-abhay/ferb-link/actions/workflows/ci.yml/badge.svg)](https://github.com/kshatriya-abhay/ferb-link/actions/workflows/ci.yml)

[Ferb](https://github.com/kshatriya-abhay/ferb) is a private Android assistant that runs a small model on the phone and has no internet permission. Ferb Link lets it use a bigger model on your own computer, without giving Ferb that permission:

- **A model server on your computer** (`server/`): llama.cpp's `llama-server` running Gemma 4 E2B, reachable only from your own devices over [Tailscale](https://tailscale.com), with an API key.
- **Ferb Link, an Android app** (`companion/`): it holds the internet permission and passes Ferb's requests, typed or spoken, to that server. Ferb talks to it on the phone and stays offline itself.

When the computer is off, asleep or unreachable, Ferb answers on the phone as before.

```
Ferb ──(on the phone)──▶ Ferb Link ──(Tailscale)──▶ llama-server + Gemma 4 E2B on your computer
```

## What you need

- **A Linux computer** with an NVIDIA GPU with at least 4 GB of memory (the CUDA build), or another GPU that runs Vulkan (the Vulkan build). Gemma 4 E2B with its audio and image encoder takes about 3 GB of GPU memory and 4 GB of disk, in `~/.local/share/ferb-link/models` (`MODELS_DIR` in `server/server.conf`). Put it on an SSD: from a spinning disk, a load after long idle takes about a minute.
- **[Tailscale](https://tailscale.com)** on the computer and the phone, on the same tailnet, with MagicDNS on (the default).
- **[mise](https://mise.jdx.dev)**, plus `curl` and `jq`, and `qrencode` for the pairing QR code.
- **An Android phone** (Android 13 or later) with Ferb and Ferb Link ([Install on your phone](#install-on-your-phone)).

## Quick start

1. **Clone this repo** and trust its mise config:
   ```sh
   git clone https://github.com/kshatriya-abhay/ferb-link
   cd ferb-link
   mise trust
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
7. **Install Ferb and Ferb Link on the phone** ([Install on your phone](#install-on-your-phone)).
8. **Pair:** run `mise run server:pair` and scan the QR code with the phone's camera. If the camera only shows the link, copy it and tap **Paste link** in Ferb Link. Ferb Link shows the computer's name: tap **Connect**. (**Enter by hand** takes the address from step 6 and the key from `mise run server:key`.)
9. **In Ferb**, open **Settings → Choose your preferred model** and pick **Gemma 4 (via Ferb Link)**: it runs on your own server and is multilingual. Then ask something: the reply has a laptop icon.

## Install on your phone

Get both apps from the same place: releases with releases (GitHub or Obtainium), your own builds with your own builds. Ferb Link answers only apps signed with its own key, and Android won't update an app across keys: switching means uninstalling, which removes the app's data.

**From GitHub releases.** On the phone, open [Ferb Link's latest release](https://github.com/kshatriya-abhay/ferb-link/releases/latest) and download `ferb-link-<version>.apk`. Open it. Android asks once to allow installs from the browser or files app. Updates install the same way, and settings stay. Do the same for [Ferb](https://github.com/kshatriya-abhay/ferb/releases/latest). To check a download, compare its SHA-256 with the `.sha256` file next to it.

**With [Obtainium](https://github.com/ImranR98/Obtainium)**, which installs from GitHub releases and keeps the apps up to date:
1. Install Obtainium (its site or F-Droid).
2. Tap **Add app**, paste `https://github.com/kshatriya-abhay/ferb-link`, and tap **Add**.
3. Tap **Install**. Obtainium then offers each update, and Android asks you to confirm it.

Add `https://github.com/kshatriya-abhay/ferb` the same way. Android installs an update only if it's signed with the same key, so a tampered APK can't replace either app.

Then pair it with your computer: [Quick start](#quick-start), step 8.

What leaves the phone, and what's stored on it: [PRIVACY.md](PRIVACY.md).

## Troubleshooting

| Problem | What to do |
|---|---|
| `server:start` says something is missing | It names the step: `server:install` (llama-server or the key) or `server:model gemma-4-e2b` (the model or its encoder). |
| The server doesn't come up | `mise run server:logs`. With the CUDA build, check `nvidia-smi` works; otherwise try `mise run server:install vulkan`. Out of GPU memory: set `GPU_LAYERS` lower in `server/server.conf`, or `auto`. |
| `server:tailnet on` says access denied | Quick start step 2: `sudo tailscale set --operator=$USER`. |
| The camera doesn't open Ferb Link from the QR code | Some camera apps don't open `ferblink://` links: copy the link it shows and tap **Paste link**. |
| Ferb Link: "can't reach your computer" | The phone's Tailscale is connected? `mise run server:tailnet` says it's on? The address is the full `….ts.net` name with `http://`. Ferb Link takes plain HTTP only to Tailscale names, not a `100.x` address. |
| Ferb Link: "Can't find your computer's name" with Tailscale on | The phone's Private DNS is set to a provider (NextDNS, for example), so Android skips Tailscale's DNS. Set **Settings → Network & internet → Private DNS** to **Automatic**. To keep that provider, add it as the tailnet's nameserver in Tailscale's admin console (DNS) instead. |
| Ferb Link: "key refused" | Pair again (`mise run server:pair`), or enter the key from `mise run server:key`. |
| "This pairing link doesn't work" | The server isn't on the tailnet (`mise run server:tailnet on`), or the link was cut off: run `server:pair` again. |
| The first answer after a while takes seconds | The server unloads the model after 5 minutes idle and loads it again on the next request: about 2 s, up to 30 s after a reboot. Ferb waits a few seconds, then answers on the phone. |
| Ferb doesn't see Ferb Link, or it says it's not allowed | The two apps are signed with different keys: one from Releases and one built here, or built on different computers. Uninstall one and install it the same way as the other. |
| Ferb says your computer can't hear | `mise run server:mode full` (the default), then **Check again** in Ferb Link. |

## Everyday commands

```sh
mise run server:status     # the service, whether it answers, and Tailscale Serve
mise run server:logs       # follow the server's log
mise run server:stop       # stop it (server:start again later; it also starts at login)
mise run server:unload     # free the GPU now; the next request loads the model again
mise run server:mode text  # text only, about 1 GB less GPU memory (server:mode full to go back)
mise run server:chat       # try the model in the terminal: chat, /tools for Ferb's tools, /image, /audio, /record
mise run server:key        # the API key, for Ferb Link
```

## How the server works

**Access.** The server listens on 127.0.0.1 only, so it's reachable from outside only through Tailscale Serve, and only from devices on your tailnet. Requests also need the API key; `/health` answers without it.

**Plain HTTP.** Tailscale already encrypts traffic between your devices (WireGuard), so Ferb Link uses plain HTTP, and only to `ts.net` names.

**On demand.** The model unloads after 5 minutes without a request and loads again on the next one, in 2–4 s from an SSD. Ferb asks Ferb Link to wake it when you start recording, so the load hides behind your speech.

Pinned versions, voice and text modes, other models and the GPU figures are in [server/README.md](server/README.md).

## Releases

Releases are signed with the same key as Ferb's. The certificate's SHA-256 is in [`release-cert.sha256`](release-cert.sha256); check a downloaded APK with `apksigner verify --print-certs`.

**Who can call Ferb Link.** Ferb Link answers any app signed with its key. For releases, that's Ferb, and also any other app the maintainer signs with the same release key. That's by design (it's how Android's signature checks work), but it means trusting those apps as much as Ferb.

## Build from source

For changing Ferb Link or the server. You also need:
- **Java 21**, for the app's build only: `mise install java@temurin-21` once. The build tasks use it; the server tasks don't.
- **The Android SDK** in `~/Android/Sdk` (Android Studio installs it there), with its licences accepted (`sdkmanager --licenses`), and `adb`.
- **A phone with USB debugging on**, to install and pair over USB.
- **Ferb's repo next to this one** (`git clone https://github.com/kshatriya-abhay/ferb` in the same parent folder), to build Ferb the same way. Ferb Link answers only a Ferb signed with the same key, so build both on one computer.

```sh
mise run companion:install                   # build and install a debug Ferb Link over USB
mise run companion:pair                      # pair it over USB instead of the QR code
mise run companion:test                      # unit and contract tests
mise run companion:send-over-tailscale <phone>   # install over Taildrop instead of USB
mise run companion:try "set a timer for 5"   # ask Ferb Link through adb, as Ferb would (debug builds)
```

Ferb Link's screens, its contract with Ferb, and its tests are in [companion/README.md](companion/README.md). With Ferb's repo next to this one, `server:start` refuses to start while Ferb's bench runs (models run one at a time), and `server:chat` offers Ferb's tools.

**Making a release** (the maintainer): `mise run companion:release` builds and signs `dist/ferb-link-<version>.apk` and its `.sha256`, and `mise run companion:publish` creates GitHub release `v<version>`. `companion:release` asks for the key alias and keystore password unless `FERB_RELEASE_KEY_ALIAS` and `FERB_RELEASE_STORE_PASSWORD` are set (`FERB_RELEASE_KEYSTORE` names the keystore; `FERB_RELEASE_KEY_PASSWORD` only if the key's password differs). They reach `apksigner` through the environment only. The certificate must match `release-cert.sha256`. Before signing, `release/check-reflection.sh` checks that R8 kept every class Android creates by name. `companion:publish` refuses without a terminal, with uncommitted or unpushed changes, when the tag exists, or when the certificate doesn't match.

## Credits

| Project | Licence | Used for |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | The model server (downloaded by `server:install`, not included) |
| [Gemma 4 E2B](https://huggingface.co/google/gemma-4-E2B-it) by Google, GGUF and projector by [Unsloth](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF) | Apache-2.0 | The default model, with its vision and audio encoder (downloaded by `server:model`) |
| [FunctionGemma 270M](https://huggingface.co/google/functiongemma-270m-it) by Google (GGUF by Unsloth) | Gemma terms | Optional model in `server/models.txt` |
| [LFM2.5-350M, LFM2.5-1.2B](https://huggingface.co/LiquidAI) by Liquid AI | LFM Open License | Optional models |
| [Qwen3.5-0.8B, Qwen3.5-2B](https://huggingface.co/Qwen) by Alibaba (GGUF by Unsloth) | Apache-2.0 | Optional models |
| [Hammer2.1-0.5b](https://huggingface.co/MadeAgents/Hammer2.1-0.5b) by MadeAgents (GGUF by mradermacher) | CC-BY-NC-4.0 | Optional model |
| [Jev](https://docs.typesafe.ai) by TypeSafe AI | Cloud API (TypeSafe's terms), no weights | Picks the tool for Ferb when the user turns it on (`decide`; off by default, the user's own TypeSafe key) |
| [qrencode](https://fukuchi.org/works/qrencode/) | LGPL-2.1 (library), GPL (tool) | `server:pair`'s QR code (optional, installed separately) |
| [Tailscale](https://tailscale.com) | BSD-3-Clause (client) | The network between phone and computer (installed separately) |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Compose, Material 3, Lifecycle, Core) | Apache-2.0 | Ferb Link's interface |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache-2.0 | The Material 3 window theme behind Compose |
| [Material Icons and Symbols](https://github.com/google/material-design-icons) | Apache-2.0 | Icons, including the app icon ("link") and the mic |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | Background work |
| [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization), [Guava ListenableFuture](https://github.com/google/guava), [JetBrains annotations](https://github.com/JetBrains/java-annotations), [JSpecify](https://github.com/jspecify/jspecify) | Apache-2.0 | Pulled in by AndroidX; listed with the rest in the app's Open-source licences |
| [JUnit 4](https://junit.org/junit4/), [Truth](https://github.com/google/truth), [JSON-java](https://github.com/stleary/JSON-java) | EPL-1.0, Apache-2.0, Public domain | Tests only (not shipped) |

## License

Apache-2.0: see [LICENSE](LICENSE). The models and llama.cpp are downloaded under their own licences, listed above.
