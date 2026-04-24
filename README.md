# android-llm-server

A headless Android app that runs local LLMs (via `llama.cpp`) and exposes them as an **OpenAI-compatible HTTP API** on your LAN. Optional image generation via `stable-diffusion.cpp`.

Repurpose an old Android phone as an always-on inference endpoint that Home Assistant, laptops, scripts, or any OpenAI SDK can call — without Termux.

## Why

Every other Android LLM app in the ecosystem ships a chat UI and runs the model in-process. None expose an HTTP server over the local network. The only prior path was Termux + a self-built `llama.cpp`, which is fragile. This app gives you a single APK with a foreground service, a persistent notification, auto-start on boot, and a proper API.

## Features

- **OpenAI-compatible API** — `POST /v1/chat/completions` (streaming + non-streaming), `GET /v1/models`, `POST /v1/images/generations`
- **Ollama-compatible API** — `GET /api/tags`, `POST /api/chat` (works with Home Assistant's Ollama integration out of the box)
- **Foreground service** — survives screen-off, uses `foregroundServiceType="specialUse"` for Android 14+, holds a partial wakelock
- **Auto-start on boot** — `BOOT_COMPLETED` receiver restores the last-used model and server state
- **Bearer-token auth** — first run generates a token, shown in Settings with Copy
- **Model manager** — curated catalog (Home Assistant-tuned, Qwen, Gemma, Llama, DeepSeek-R1) plus paste-your-own HuggingFace URL, with resumable downloads and SHA256 verification
- **GPU acceleration** — optional Vulkan backend for Adreno (tested on Note 10+)
- **Vision / multimodal** — image input for supported models
- **Image generation** — Stable Diffusion models via `stable-diffusion.cpp` submodule, routed through the same server
- **DeepSeek-R1 thinking** — reasoning display with stripping of residual `<think>` tags
- **Built-in chat screen** — for quick local testing (the primary contract is still the API)

## Requirements

- Android 10+ (minSdk 29, targetSdk 35)
- arm64 device (the APK ships `arm64-v8a` only — no x86 emulator support)
- ~1–4 GB of free storage per model
- Enough RAM to hold your chosen model plus overhead (6–8 GB recommended for 1.5–3B Q4 models)

## One-line install

Clones the repo, installs the toolchain (JDK 17, Android SDK/NDK/CMake), and leaves you ready to `make build`.

**macOS, Linux, WSL:**

```bash
curl -fsSL https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.sh | bash
```

**Windows PowerShell:**

```powershell
irm https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.ps1 | iex
```

**Windows CMD:**

```cmd
curl -fsSL https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.cmd -o install.cmd && install.cmd && del install.cmd
```

Env overrides: `ALSER_DIR` (target directory), `ALSER_REPO`, `ALSER_REF` (branch/tag), `ALSER_SKIP_SETUP=1` to clone without installing the toolchain.

## Build (manual)

Prerequisites: Android Studio Hedgehog (2023.1.1) or newer, NDK, CMake 3.22.1, JDK 17.

```bash
git clone --recurse-submodules https://github.com/zeeshanhaque21/android-llm-server.git
cd android-llm-server
./scripts/setup.sh   # or scripts\setup.ps1 on Windows
make build
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Submodules:
- `app/src/main/cpp/llama.cpp` — inference engine
- `app/src/main/cpp/stable-diffusion.cpp` — image generation

Build is `arm64-v8a` only and uses `-O3`. Native builds parallelize across 24 cores by default (see `app/build.gradle.kts`).

## Install and run

1. `adb install app-debug.apk` (or sideload the APK directly)
2. Launch the app, grant notification permission, disable battery optimization when prompted
3. Go to **Models** → pick one from the catalog → Download
4. Go to **Settings** → note the bearer token and port (default `8080`), optionally toggle "Start on boot"
5. Tap **Start server** on the main screen — a persistent notification appears

From another device on the LAN:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5-1.5b-instruct-q4_k_m.gguf",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

## Home Assistant integration

Point the **Ollama** integration at `http://<phone-ip>:8080` with the bearer token. The `Home-3B-v3` and `Home-1B-v3` models in the catalog are fine-tuned for HA device control.

## Architecture

```
app/src/main/
├── cpp/
│   ├── llm_bridge.cpp          JNI surface for llama.cpp
│   ├── sd_bridge.cpp           JNI surface for stable-diffusion.cpp
│   ├── llama.cpp/              submodule
│   └── stable-diffusion.cpp/   submodule
└── kotlin/com/zeeshan/androidllmserver/
    ├── MainActivity.kt
    ├── service/LlmService.kt           foreground service, owns the model + HTTP server
    ├── http/                            Ktor/Netty routing (OpenAI + Ollama + images)
    ├── llm/LlmBridge.kt                 Kotlin wrapper over native inference
    ├── sd/SdBridge.kt                   image-generation bridge
    ├── model/                           catalog, downloader, repository
    ├── auth/AuthManager.kt              bearer token generation + validation
    ├── receiver/BootCompletedReceiver.kt
    ├── prefs/ServerPreferences.kt
    └── ui/                              Compose: Chat, Models, Settings
```

Stack: Kotlin, Jetpack Compose, Ktor Server (Netty), kotlinx.serialization, coroutines with a dedicated single-thread dispatcher for the inference loop, JNI only at the native boundary.

## Non-goals

- Not a chat app — the UI is for testing; the API is the product
- No iOS, no Windows, no Linux
- Not a Play Store release — Google rejects the `specialUse` justification; distribution is direct APK / F-Droid
- No bundled models — downloaded at runtime
- No llama.cpp fork — we build upstream and pin by SHA

## Further reading

- `CLAUDE.md` — architectural principles and coding conventions
- `docs/findings.md` — research notes on the Android LLM ecosystem
- `docs/plan.md` — phased implementation plan and status

## License

See upstream licenses for `llama.cpp` (MIT) and `stable-diffusion.cpp` (MIT). Project license TBD.
