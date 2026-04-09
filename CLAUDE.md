# android-llm-server — Claude Rules

## What this project is

A native Android app that runs a local LLM (via llama.cpp) and exposes it as an **OpenAI-compatible HTTP API** on the device's network interfaces. No Termux. No chat UI. Just a headless server with a minimal configuration screen.

Target use case: repurpose an old Android phone as a LAN-accessible LLM endpoint that other devices (Home Assistant, laptops, other phones) can call like they would OpenAI or Ollama.

## Why this exists

Every existing Android LLM app in 2026 (PocketPal, SmolChat, Maid, MLC Chat, Sherpa, cparish312/llama.cpp-android) runs models in-process as a chat UI but **none expose an HTTP server over LAN**. The only way to get an API on Android is Termux + llama.cpp, which is fragile and user-hostile. See `docs/findings.md` for the full survey.

## Non-goals

- **Not a chat app.** No conversation UI beyond debug/status. Plenty of those exist already.
- **Not multi-model at runtime.** Start with one model loaded at a time. Model-switching can come later.
- **Not a Play Store app.** Target F-Droid / direct APK distribution. Google will reject the foreground service justification.
- **Not iOS.** Android only.
- **Not a llama.cpp fork.** Build against upstream, prefer their Android example as scaffolding. Do not patch llama.cpp internals — if we need changes, upstream them.

## Architectural principles

1. **llama.cpp is the engine. Don't reinvent it.** Use upstream as a git submodule or NDK external project. When they release a new version, rebuild the `.so` — do not vendor or fork.
2. **Kotlin + JNI, not all-native.** Keep the UI, service lifecycle, notification, and HTTP server in Kotlin/Ktor. Only the inference core crosses the JNI boundary. This makes Android-side debugging sane.
3. **Foreground service is non-negotiable.** Android 14+ requires `foregroundServiceType="specialUse"` with a justification string. The service owns the llama.cpp context and the HTTP server lifecycle. If the service dies, the API dies — that's the entire contract.
4. **State of the art assumption: Samsung is the adversary.** Design for One UI's aggressive killer. Every lifecycle event, every wakelock acquire/release, every notification update must assume the OS is trying to kill us.
5. **One process, one model.** No process isolation for the inference runtime. Memory is the scarce resource; double-buffering models would OOM immediately.
6. **HTTP, not just WebSocket.** OpenAI-compatible REST (`/v1/chat/completions`, `/v1/models`) is the primary contract. Streaming via SSE. No custom protocol.

## What "done" looks like for v1

- Install the APK, grant permissions, tap "Download model" → it pulls a GGUF from a URL into app-private storage
- Tap "Start server" → foreground notification appears, HTTP server starts on configurable port (default 8080)
- From another device on the LAN: `curl http://<phone-ip>:8080/v1/chat/completions -d '...'` returns a valid OpenAI-format response
- Server survives screen-off for at least 30 minutes (with the phone plugged in, wakelock held)
- Server auto-restarts on device boot via `BOOT_COMPLETED` receiver
- If the service is killed, a persistent notification tells the user and offers a one-tap restart

## Coding conventions

- **Kotlin** for everything non-native. Idiomatic, no Java.
- **Jetpack Compose** for the UI. No XML layouts.
- **Ktor Server (Netty)** for the HTTP layer. Not OkHttp, not custom.
- **Coroutines** for concurrency. No callbacks, no `Thread.start()`. The inference loop runs on `Dispatchers.Default` with a dedicated single-thread dispatcher so model state doesn't race.
- **JNI layer lives in `app/src/main/cpp/`** with a CMakeLists that pulls llama.cpp as a submodule or `ExternalProject_Add`. Expose minimal C surface: `init(model_path)`, `chat_completion(prompt, params, callback)`, `cancel()`, `free()`.
- **No reflection, no code generation** beyond what Compose/Hilt already do.
- **`package com.zeeshan.androidllmserver`** — change if the user has a different preference.

## Directory layout (target)

```
android-llm-server/
├── CLAUDE.md               ← this file
├── .gitignore
├── docs/
│   ├── findings.md         ← research + ecosystem survey
│   ├── plan.md             ← phased implementation plan
│   └── architecture.md     ← (future) diagrams + component breakdown
├── app/                    ← Android module
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── cpp/            ← JNI + llama.cpp submodule
│   │   ├── kotlin/com/zeeshan/androidllmserver/
│   │   │   ├── MainActivity.kt
│   │   │   ├── service/    ← ForegroundService, lifecycle
│   │   │   ├── http/       ← Ktor routing, OpenAI-compat handlers
│   │   │   ├── llm/        ← JNI wrapper, model manager
│   │   │   └── ui/         ← Compose screens
│   │   └── res/
│   └── src/test/
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

## When working on this repo

- **Always read `docs/plan.md` first** to find the current phase and what's next.
- **Update `docs/plan.md` after every meaningful milestone** — check off items, add discovered sub-tasks, note blockers.
- **New non-obvious findings** (Android quirks, llama.cpp API changes, Samsung-specific bugs) go in `docs/findings.md` under a dated heading.
- **Do not commit the llama.cpp submodule contents** — pin by commit SHA in `.gitmodules`.
- **Do not commit models** to the repo. Ever. They're downloaded at runtime by the user. `.gguf` in `.gitignore`.
- **Do not commit build artifacts** — `build/`, `.gradle/`, `*.apk`, `*.so` files that aren't in `libs/`.

## Testing strategy

- **Inference layer**: unit tests are impractical (needs a GGUF + native libs). Use instrumentation tests that load a tiny model (TinyLlama Q4, ~600 MB) and verify round-trip.
- **HTTP layer**: unit-test Ktor routes with `testApplication {}`. Mock the LLM binding.
- **Service lifecycle**: manual device testing on the target phone. No emulator — emulators don't reproduce One UI's kill behavior.

## Things I want you to push back on

If you're asked to:
- Add a chat UI → remind the user that SmolChat/Maid already exist and this project is deliberately headless
- Support Windows/iOS/Linux → out of scope, this is an Android app
- Publish to Google Play → won't work, Google rejects these; F-Droid or direct APK is the plan
- Bundle a default model in the APK → APKs can't be multi-GB; the model must be downloaded at runtime
- Use a different inference engine (MLX, ONNX Runtime, MLC) → we committed to llama.cpp because it has the widest model support and best ARM NEON perf. Changing engines is a bigger decision than "pick a library"

Ask before making any of those changes.
