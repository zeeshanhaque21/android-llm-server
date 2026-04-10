# Implementation Plan — android-llm-server

**Current phase: 1 (in progress)**

This plan is phased so each phase ends with a testable artifact. Do not start Phase N+1 until Phase N's acceptance criteria are met. Update this file as phases progress — check items off, add discovered sub-tasks inline, note blockers under the phase they affect.

---

## Phase 0 — Project scaffolding

**Goal:** empty Android Studio project that builds an APK that installs, runs, and shows a blank screen.

- [x] Install Android Studio (if not already) — minimum Hedgehog (2023.1.1) for AGP 8.2
- [x] Create new project: **Empty Activity (Compose)**, Kotlin, min SDK 29, target SDK 35, package `com.zeeshan.androidllmserver`
- [x] Migrate the generated project into this repo (`app/` directory)
- [x] Add `.gitignore` for Android (the standard one — `build/`, `.gradle/`, `local.properties`, `*.apk`, `*.iml`, `.idea/`)
- [ ] Verify `./gradlew assembleDebug` produces an APK
- [ ] Install on the Note 10+ via `adb install` or Android Studio, confirm blank screen loads
- [ ] Commit

**Acceptance:** `./gradlew assembleDebug` succeeds, APK installs, app launches to blank Compose screen without crashing.

---

## Phase 1 — llama.cpp integration (no UI, no server)

**Goal:** prove we can load a GGUF model and generate tokens from Kotlin on the phone. Pure JNI wiring. No HTTP yet.

- [x] Add llama.cpp as a git submodule (pinned at d132f22)
- [x] Create `app/src/main/cpp/CMakeLists.txt` that builds:
  - llama.cpp as a static lib (`LLAMA_BUILD_EXAMPLES=OFF`, `LLAMA_BUILD_TESTS=OFF`, `LLAMA_BUILD_SERVER=OFF`)
  - our thin JNI shim (`llm_bridge.cpp`) that links against it
- [x] Wire CMake into Gradle via `externalNativeBuild { cmake { ... } }` in `app/build.gradle.kts`
- [x] Implement minimal JNI surface in `llm_bridge.cpp`:
  - `Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeInit(jstring modelPath): jlong` → returns opaque context pointer
  - `Java_..._nativeGenerate(jlong ctx, jstring prompt, jint maxTokens, jobject callback): void` → calls callback per token
  - `Java_..._nativeCancel(jlong ctx): void`
  - `Java_..._nativeFree(jlong ctx): void`
- [x] Kotlin wrapper `LlmBridge.kt` exposing these as suspending functions via a dedicated single-thread dispatcher
- [x] Write a manual smoke test: add a temporary button to MainActivity that loads a model from `/sdcard/Download/qwen2.5-1.5b-q4.gguf` and prints tokens to logcat
- [ ] Push the same GGUF we used in Termux (md5 `8e5111fdbc5c150920d368ff802c4b5a`) to the phone via `adb push`
- [ ] Run the smoke test, verify tokens stream to logcat at roughly the Termux baseline (~14 tok/s)
- [ ] Commit

**Acceptance:** tapping the test button in the app produces streaming tokens in logcat from qwen2.5:1.5b at within 5% of the 14.45 tok/s Termux baseline. If performance regresses, investigate before proceeding.

**Blockers / known risks:**
- llama.cpp build may fail on NDK due to the same `spawn.h` issue we hit in Termux. If so, disable `LLAMA_BUILD_SERVER` at CMake level — the missing include is only in `tools/server/server-tools.cpp` which we're not building.
- Compiler flags matter for perf. Ensure `-march=armv8.2-a+dotprod` or native, `-O3`, and `GGML_USE_OPENBLAS=OFF` (we want llama.cpp's own kernels).

---

## Phase 2 — Foreground service + notification

**Goal:** move the LLM runtime out of the activity and into a foreground service that survives screen-off. No HTTP yet, but inference must work from the service.

- [ ] Create `LlmService.kt` extending `LifecycleService`
- [ ] Declare in manifest with `foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="local AI inference server for LAN clients" />`
- [ ] Request permissions at runtime: `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- [ ] Create high-priority notification channel, build persistent notification showing "LLM server — idle" / "LLM server — generating"
- [ ] Service lifecycle:
  - `onStartCommand` → `startForeground(NOTIF_ID, buildNotification())`
  - Acquire partial wakelock
  - Initialize `LlmBridge` with the model path passed via intent extra
  - On `STOP` intent → release wakelock, free model, `stopForeground`
- [ ] `MainActivity` becomes a minimal control screen: "Start server" / "Stop server" button, status text
- [ ] Move the Phase 1 smoke test to a service method, trigger via intent from the activity
- [ ] Manual test: start service, press home, wait 5 minutes, verify service is still running and can still generate

**Acceptance:** the service runs with screen off for 30+ minutes and still responds to inference requests.

**Samsung-specific sub-tasks:**
- [ ] Document the One UI battery exemption steps in a first-run onboarding screen
- [ ] Test on the Note 10+: does the service survive 1 hour of screen-off? 4 hours? Overnight?

---

## Phase 3 — HTTP server (Ktor) with minimal OpenAI routes

**Goal:** the service exposes `POST /v1/chat/completions` and `GET /v1/models` on a configurable port. Non-streaming first.

- [ ] Add Ktor Server Netty dependencies
- [ ] Create `HttpServer.kt` that owns the Ktor embedded server instance
- [ ] Bind to `0.0.0.0:<port>` where port comes from SharedPreferences (default 8080)
- [ ] Routes:
  - `GET /v1/models` → return JSON listing the currently loaded model
  - `POST /v1/chat/completions` (non-streaming) → accept OpenAI request, call `LlmBridge.generate`, return OpenAI response
  - `GET /health` → simple OK for liveness checks
- [ ] OpenAI-compatible request/response DTOs — use kotlinx.serialization. Match the shapes from the reference (model, messages array with role/content, temperature, max_tokens, choices array with message and finish_reason, usage token counts)
- [ ] Wire into `LlmService`: start HTTP server in `onCreate`, stop in `onDestroy`
- [ ] Manual test from laptop: `curl http://<phone-ip>:8080/v1/chat/completions -d '...'` returns an OpenAI-format response

**Acceptance:** a `curl` from another LAN device gets a valid OpenAI-format reply containing generated tokens.

---

## Phase 4 — Streaming (SSE)

**Goal:** `stream=true` on chat completions returns Server-Sent Events, token by token, just like OpenAI.

- [ ] Detect `stream: true` in request body
- [ ] Return `Content-Type: text/event-stream`
- [ ] For each token from the JNI callback, emit `data: {"choices":[{"delta":{"content":"<token>"}}]}\n\n`
- [ ] Final event: `data: [DONE]\n\n`
- [ ] Handle client disconnect → call `LlmBridge.cancel()` → abort the native decode loop
- [ ] Test with `curl --no-buffer` and with an OpenAI-compatible client (e.g., `openai` Python SDK pointing at the phone)

**Acceptance:** streaming works end-to-end and cancellation works (disconnect curl mid-stream, verify the JNI worker actually stops and doesn't keep decoding in the background).

---

## Phase 5 — Model manager UI

**Goal:** users can download, select, and delete models without touching adb.

- [ ] `ModelsScreen.kt` — Compose list of installed models in app-private storage
- [ ] "Add model" flow:
  - Curated catalog of small models (JSON bundled in assets): qwen2.5:1.5b-q4, llama3.2:1b-q4, gemma2:2b-q4, tinyllama:1.1b-q4
  - "Paste HuggingFace URL" for power users
- [ ] Download manager using `WorkManager` with resumable downloads, progress in notification
- [ ] SHA256 verification after download
- [ ] Delete model action (with confirm)
- [ ] "Load model" action → sends intent to service to swap the active model (stops generation first)

**Acceptance:** install fresh APK, pick a model from the catalog, it downloads, loads, and the API works.

---

## Phase 6 — Auth and hardening

**Goal:** the server is safe to expose on an untrusted home network.

- [ ] Bearer token auth: first run generates a random token, shown in the UI with "Copy" button
- [ ] All `/v1/*` routes reject requests without `Authorization: Bearer <token>`
- [ ] CORS headers configurable (default: deny cross-origin)
- [ ] Rate limiting per IP (simple token bucket in Ktor interceptor)
- [ ] Setting: "Only accept localhost" for users who want to tunnel via tailscale only
- [ ] Bind behavior: if user selects "LAN" bind to `0.0.0.0`, if "localhost only" bind to `127.0.0.1`

**Acceptance:** `curl` without the token returns 401. `curl` with the token works.

---

## Phase 7 — Autostart on boot

**Goal:** server comes back automatically after reboot.

- [ ] `BootCompletedReceiver` triggered by `RECEIVE_BOOT_COMPLETED` permission
- [ ] On boot: start the service with the last-used model (stored in SharedPreferences)
- [ ] Setting: "Start server on boot" toggle (default on)
- [ ] Test: reboot phone, verify server is up within 30 seconds of boot

**Acceptance:** reboot the phone, SSH to the Note 10+ stops working *and then comes back*, and `curl http://<phone-ip>:8080/v1/models` succeeds within 30 seconds of the phone finishing boot.

---

## Phase 8 — Polish and release

- [ ] Settings screen: port, threads, context size, temperature default, auth token, bind mode, autostart toggle
- [ ] Performance tuning: expose `-t`, `-c`, `--mlock` in UI
- [ ] Logs screen (ring buffer of recent requests)
- [ ] Onboarding screen with device-specific setup instructions (detect Samsung → show One UI steps)
- [ ] Crash reporting (optional, local only — no cloud)
- [ ] README with install instructions, supported devices, known issues
- [ ] F-Droid submission: metadata, screenshots, reproducible build

**Acceptance:** a non-technical user can install the APK, follow the onboarding, and get a working LLM endpoint without reading any docs beyond the app itself.

---

## Deferred / maybe-never

- Multi-model loaded simultaneously (memory prohibitive on phones)
- Speculative decoding (nice-to-have, marginal on CPU)
- GPU backends: Vulkan/OpenCL on Android work in llama.cpp upstream but are flaky on Tegra/Mali/Adreno driver combos. Punt.
- Audio models (Whisper, Kokoro) — out of scope, keep the app focused
- iOS port — different project entirely
- Remote management (WebUI beyond the OpenAI API) — the llama.cpp web UI is nice but adds complexity; skip for v1

---

## Notes during implementation

(Append discoveries, surprises, decisions, and links to commits here as we work through the phases. Date stamps preferred.)
