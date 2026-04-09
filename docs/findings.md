# Findings — Android LLM server landscape (April 2026)

## The gap we're filling

Every existing Android LLM app runs models **in-process as a chat UI**. None of them expose an HTTP server to the LAN. The only way to get an OpenAI-compatible API on Android today is **Termux + llama.cpp's `llama-server` binary**, which works but requires terminal knowledge and fights Android's background-process model constantly.

## Ecosystem survey

| App | Local inference | HTTP API over LAN | Status | Notes |
|---|---|---|---|---|
| **PocketPal AI** | ✅ GGUF via llama.cpp | ❌ | Play Store, active | Feature request for OpenAI endpoint has been open since early 2025 ([issue #407](https://github.com/a-ghorbani/pocketpal-ai/issues/407)), not merged |
| **SmolChat** | ✅ GGUF | ❌ | Active, F-Droid | Clean JNI binding to llama.cpp. Best base to fork if we go that route. |
| **Maid** | ✅ GGUF via llama.cpp | ❌ (client only — can connect to *remote* llama-server but not host one) | Active, Flutter | Cross-platform, privacy-first framing |
| **MLC LLM Chat (Android)** | ✅ MLC format | ❌ | Active | MLCEngine itself supports REST API, but the Android app is chat-only |
| **Sherpa** | ✅ | ❌ | Unmaintained | Old llama.cpp Android port on Play Store |
| **cparish312/llama.cpp-android** | ✅ | ❌ | Active | Solid NDK build example, no server layer |
| **Google AI Edge Gallery** | ✅ (Gemma only) | ❌ | Google-official | Closed ecosystem |
| **dineshsoudagar/local-llms-on-android** | ✅ (ONNX Runtime) | ❌ | Active | Different engine; chat-only |

**Conclusion: the niche is genuinely unfilled.** Building this app is not reinventing anything — it's closing an obvious gap.

## Why no one has done it

Two real reasons, both worth understanding before we start:

1. **Android makes it hard.** Long-running network services on Android 14+ require `foregroundServiceType="specialUse"` with a justification string, plus persistent notification, plus battery exemption dance. Samsung One UI aggressively kills foreground services it deems "idle." Google Play will reject the app on first review unless the foreground service type is justified to their reviewers' satisfaction — and "I run an LLM server" is not on their approved list.
2. **The demand is small.** Most people who want a local LLM API run it on a desktop/Jetson/Pi, not a phone. The "I want to repurpose my old phone as an LLM endpoint" use case is real but niche. Commercial incentive is weak.

Neither reason is a *technical* blocker. Both are solvable with discipline.

## What exists that we can reuse

- **`llama.cpp/docs/android.md`** — official NDK build instructions. Cross-compile to `arm64-v8a`, get a `libllama.so`.
- **`llama.cpp/examples/llama.android/`** — upstream Kotlin/JNI example. Working starting point. Already shows how to load a model and generate tokens from a Kotlin `LLamaAndroid` class. No server layer.
- **`llama.cpp/tools/server/`** — the reference `llama-server` implementation in C++. ~5000 lines. We could embed this directly via JNI or reimplement the HTTP layer in Kotlin (Ktor) and call into llama.cpp's lower-level API. Ktor path is cleaner.
- **`cpp-httplib`** — header-only C++ HTTP server that `llama-server` uses. If we go the native-HTTP route we pull this in.
- **Ktor Server (Netty)** — JetBrains' Kotlin HTTP server. Coroutine-native, minimal config, SSE support built-in. Best fit for our Kotlin-side server.

## Hard problems we will hit (in order of pain)

### 1. Samsung One UI's foreground service killer

Samsung's battery optimization ignores most of Android's standard APIs. Even with "Unrestricted" set, One UI will kill foreground services it deems idle after a few hours. This is **the same problem that would affect Termux**, so a native app doesn't automatically fix it — but we have more levers to pull:

- `setForeground()` with a high-priority notification channel
- Wakelock acquire/release around inference requests
- `JobScheduler` heartbeat to wake the service periodically
- Display a "tap to revive" notification with a deep link when killed
- Document the Samsung-specific settings the user must flip:
  - Device care → Battery → Background usage limits → Never sleeping apps
  - Settings → Apps → ourapp → Battery → Unrestricted
  - Settings → Apps → ourapp → Pause app activity if unused → OFF

### 2. Android 14+ foreground service type enforcement

Starting in API 34, every foreground service must declare a `foregroundServiceType` in the manifest. Valid types include `dataSync`, `connectedDevice`, `mediaPlayback`, `specialUse`, etc. None of them fit "local AI inference server" perfectly. Best match is **`specialUse`** with a `specialUseSubtype` meta-data value explaining the purpose. Misdeclaring the type causes `ForegroundServiceTypeException` on service start.

We'll also need to request runtime permission `POST_NOTIFICATIONS` on API 33+ (Android 13+).

### 3. Model storage and size

APKs are capped at 150 MB on Play Store (or 200 MB with app bundle). GGUF models for usable LLMs start at ~600 MB (TinyLlama Q4) and go up. **The model cannot ship in the APK.** It must be downloaded at runtime into app-private storage (`context.filesDir` or `context.getExternalFilesDir()`). This means:

- First-run UX: "Select or download a model"
- Bundled model catalog (a JSON list of HuggingFace URLs for common small models)
- Resume-capable download manager
- Checksum verification

### 4. Threading and the JNI boundary

llama.cpp's context is not thread-safe across decode calls. We must serialize all inference requests to a single worker thread. Kotlin `Dispatchers` give us this cleanly via a dedicated single-thread dispatcher, but we must:

- Route all HTTP requests through a `Channel<InferenceRequest>` consumed by the worker
- Support cancellation (client disconnects mid-stream) via `CoroutineScope.cancel()` → native `llama_abort()` call
- Stream tokens via SSE as they arrive, not batch them

### 5. Memory pressure and OOM

An 8 GB phone running a 1.5B Q4 model leaves roughly 4-6 GB free depending on how much Android is already using. Loading a 3B model on 8 GB is marginal. The OS kills the app before it gets a chance to clean up. We need:

- `largeHeap="true"` in manifest (doesn't help much but doesn't hurt)
- Active RSS monitoring in the service — warn the user if RAM drops below a safety threshold
- Advise model size based on detected device RAM during onboarding
- Never load two models simultaneously, even for "switching"

### 6. llama.cpp API churn

llama.cpp changes its C API regularly. Pinning to a specific commit in the submodule is essential. Rebuilding after upstream updates requires re-checking the JNI bindings, because function signatures change. **Pin and test before updating.**

## Hardware assumptions

**Minimum viable target device** for v1:

- Android 10+ (API 29+) for reasonable NDK support
- ARMv8-A / arm64-v8a (no 32-bit — it's 2026)
- 6+ GB RAM (comfortable), 8+ GB preferred
- Cortex-A76 or newer core (for reasonable NEON perf)
- Samsung One UI, Google Pixel stock, OxygenOS — must test on all three because they all kill background services differently

**Reference device for development:** Samsung Galaxy Note 10+ (SD 855, 12 GB RAM, One UI 4 on Android 12). Achieves 14.45 tok/s on `qwen2.5:1.5b-q4_k_m` via Termux+llama.cpp — this is our baseline to match (or beat) with the native app. If the native app performs meaningfully worse, something is wrong in the build config.

## Performance baseline (from Termux llama-server on Note 10+)

```
Model:       qwen2.5:1.5b-instruct-q4_k_m  (~1 GB)
Build:       llama.cpp, -DGGML_NATIVE=ON, -j4
Threads:     6
Context:     4096
Prompt:      "Write exactly 30 words about home automation."
Eval rate:   14.45 tok/s
```

This is the number to beat. The native app build should match within 5% — if it regresses, investigate compiler flags.

## Open questions to decide during Phase 1

- **JNI bridge style**: (a) expose only primitive llama.cpp calls and build the chat templating / sampling loop in Kotlin, or (b) expose a higher-level `generate(prompt, params)` streaming callback from C++. **Leaning toward (b)** because it's less JNI traffic per token and lets us reuse llama-server's sampling code.
- **HTTP server language**: (a) Ktor in Kotlin, calling into JNI per request, or (b) embed `cpp-httplib` natively and run the HTTP loop in C++. **Leaning toward (a)** because Kotlin makes Android service lifecycle integration trivial.
- **Model download UX**: (a) bundled catalog of pinned HF URLs, or (b) let user paste any HF repo URL. **Probably both** — catalog for convenience, URL input for power users.
- **Authentication**: v1 should support at least a shared-secret bearer token, because "an HTTP server on an Android phone on a home network" is exactly the kind of thing people footgun.
