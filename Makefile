# android-llm-server Makefile
# Thin wrapper around gradle + adb + gh for the common day-to-day tasks.
# Run `make help` to list targets.

APP_ID        := com.zeeshan.androidllmserver
MAIN_ACTIVITY := $(APP_ID)/.MainActivity
DEBUG_DIR     := app/build/outputs/apk/debug
RELEASE_DIR   := app/build/outputs/apk/release
GRADLE        := ./gradlew

# APK filenames follow android-llm-server-v<version>-<buildType>.apk
# (set by applicationVariants.all in app/build.gradle.kts). We glob to be
# resilient to version bumps and to fall back to the default app-* names
# in case someone tweaks the variant config.
debug_apk   = $(firstword $(wildcard $(DEBUG_DIR)/android-llm-server-*-debug.apk $(DEBUG_DIR)/app-debug.apk))
release_apk = $(firstword $(wildcard $(RELEASE_DIR)/android-llm-server-*-release.apk $(RELEASE_DIR)/app-release.apk $(RELEASE_DIR)/app-release-unsigned.apk))

# Gradle 8.10 refuses to launch on JDK 24+, and this project targets
# Java 17 bytecode. If the caller didn't set JAVA_HOME, scan the usual
# install paths (Linux distro dirs, macOS java_home, Homebrew).
ifeq ($(strip $(JAVA_HOME)),)
JAVA_HOME := $(shell \
  for d in \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-arm64 \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/temurin-17-jdk-arm64 \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do \
    [ -x "$$d/bin/java" ] && echo "$$d" && exit 0; \
  done; \
  if [ -x /usr/libexec/java_home ]; then /usr/libexec/java_home -v 17 2>/dev/null; fi)
endif
export JAVA_HOME

# Release artifact naming + upload target
VERSION ?= $(shell grep '^\s*versionName' app/build.gradle.kts | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
TAG     ?= v$(VERSION)
GH_REPO ?= $(shell git config --get remote.origin.url 2>/dev/null | sed -E 's#.*[:/]([^/]+/[^/.]+)(\.git)?$$#\1#')

.PHONY: help setup submodules build debug release install install-release run \
        logcat stop uninstall clean distclean lint test tag publish publish-draft \
        apk-info devices convert-model convert-batch convert-setup

help:
	@echo "android-llm-server — make targets"
	@echo ""
	@echo "  setup          Install toolchain (JDK, Android SDK/NDK/CMake) for this OS"
	@echo "  submodules     git submodule update --init --recursive"
	@echo ""
	@echo "  build          Assemble debug APK"
	@echo "  release        Assemble release APK"
	@echo "  lint           Run Android lint"
	@echo "  test           Run unit tests"
	@echo "  clean          gradle clean"
	@echo "  distclean      clean + wipe build/ and .gradle/"
	@echo ""
	@echo "  devices        List connected adb devices"
	@echo "  install        adb install the debug APK to the connected device"
	@echo "  install-release adb install the release APK"
	@echo "  run            Install debug APK and launch MainActivity"
	@echo "  stop           Force-stop the running app"
	@echo "  uninstall      Remove the app from the connected device"
	@echo "  logcat         Tail logcat for $(APP_ID)"
	@echo ""
	@echo "  convert-setup  Create venv + install ai-edge-torch / litert-lm-builder deps"
	@echo "  convert-model  Convert HF or GGUF -> .litertlm. Usage:"
	@echo "                   make convert-model HF=meta-llama/Llama-3.2-1B-Instruct OUT=~/llama.litertlm"
	@echo "                   make convert-model GGUF=~/qwen.gguf OUT=~/qwen.litertlm"
	@echo "  convert-batch  Parallel convert every *.gguf in a directory (each worker"
	@echo "                 needs ~18 GB RAM; default 2 jobs). Usage:"
	@echo "                   make convert-batch DIR=~/Downloads JOBS=2"
	@echo ""
	@echo "  tag            Create git tag $(TAG) at HEAD"
	@echo "  publish        Build release APK and upload to GitHub release $(TAG)"
	@echo "  publish-draft  Same as publish, but marks the release as a draft"
	@echo "  apk-info       Print version/size of built APKs"

# ---------- toolchain ----------

setup:
ifeq ($(OS),Windows_NT)
	powershell -ExecutionPolicy Bypass -File scripts/setup.ps1
else
	bash scripts/setup.sh
endif

submodules:
	git submodule update --init --recursive

# ---------- build ----------

build: submodules
	@bash scripts/fix-host-tools.sh
	@$(GRADLE) assembleDebug || ( \
	  echo "[build] gradle failed — attempting fix-host-tools and retrying once"; \
	  bash scripts/fix-host-tools.sh && $(GRADLE) assembleDebug )

debug: build

release: submodules
	@bash scripts/fix-host-tools.sh
	@$(GRADLE) assembleRelease || ( \
	  echo "[release] gradle failed — attempting fix-host-tools and retrying once"; \
	  bash scripts/fix-host-tools.sh && $(GRADLE) assembleRelease )

lint:
	$(GRADLE) lint

test:
	$(GRADLE) test

clean:
	$(GRADLE) clean

distclean: clean
	rm -rf build app/build .gradle app/.cxx

# ---------- device ----------

devices:
	adb devices -l

install: build
	adb install -r $(debug_apk)

install-release: release
	adb install -r $(release_apk)

run: install
	adb shell am start -n $(MAIN_ACTIVITY)

stop:
	adb shell am force-stop $(APP_ID)

uninstall:
	adb uninstall $(APP_ID)

logcat:
	@echo "Tailing logcat for $(APP_ID) — Ctrl-C to stop"
	adb logcat --pid=$$(adb shell pidof -s $(APP_ID))

# ---------- model conversion (HF / GGUF -> .litertlm) ----------
# These targets are independent of the Android build. They drive
# scripts/convert_to_litertlm.py to produce a .litertlm file you can
# sideload into the app. Conversion runs entirely on your laptop —
# the toolchain is too heavy for on-device.

# Override on the command line: HF=<repo> OR GGUF=<path>; OUT=<file>.
HF    ?=
GGUF  ?=
OUT   ?=
ARCH  ?=
VARIANT ?=
QUANT ?= dynamic_int8
CONVERT_VENV ?= $(HOME)/.venvs/litertlm-convert

convert-setup:
	@# ai-edge-torch's hard dep on Python 3.9-3.11 forces a pinned interpreter.
	@# Use uv (https://docs.astral.sh/uv/) so we don't need root to install
	@# a side-by-side Python — and its venv is faster than stdlib venv.
	@command -v uv >/dev/null 2>&1 || (echo \
	  "uv not found. Install with: curl -LsSf https://astral.sh/uv/install.sh | sh" \
	  && exit 1)
	@uv python install 3.11 >/dev/null
	@if [ ! -d "$(CONVERT_VENV)" ]; then \
	  echo "[convert] creating Python 3.11 venv at $(CONVERT_VENV)"; \
	  uv venv "$(CONVERT_VENV)" --python 3.11 --quiet; \
	fi
	@VIRTUAL_ENV="$(CONVERT_VENV)" uv pip install \
	  -r scripts/convert_to_litertlm.requirements.txt
	@echo "[convert] activate the venv with:  source $(CONVERT_VENV)/bin/activate"

convert-model:
	@test -n "$(OUT)" || (echo "OUT=<path/to/output.litertlm> is required" && exit 1)
	@test -n "$(HF)$(GGUF)" || (echo "Pass either HF=<repo> or GGUF=<path>" && exit 1)
	@if [ ! -d "$(CONVERT_VENV)" ]; then \
	  echo "[convert] venv missing — run 'make convert-setup' first"; exit 1; \
	fi
	@SRC=$$(if [ -n "$(HF)" ]; then echo "--hf $(HF)"; else echo "--gguf $(GGUF)"; fi); \
	 ARGS=""; \
	 [ -n "$(ARCH)" ]    && ARGS="$$ARGS --arch $(ARCH)"; \
	 [ -n "$(VARIANT)" ] && ARGS="$$ARGS --variant $(VARIANT)"; \
	 . "$(CONVERT_VENV)/bin/activate" && \
	 python3 scripts/convert_to_litertlm.py $$SRC --out $(OUT) --quantize $(QUANT) $$ARGS

# Batch driver: scan a directory of GGUFs (or pass a list of HF repos via
# HFS="repo1 repo2 ...") and convert in parallel. JOBS bounds RAM use —
# each worker is ~18 GB. Output filenames are derived from inputs.
DIR    ?=
HFS    ?=
JOBS   ?= 2
OUTDIR ?= $(HOME)/Downloads
convert-batch:
	@if [ ! -d "$(CONVERT_VENV)" ]; then \
	  echo "[convert] venv missing — run 'make convert-setup' first"; exit 1; \
	fi
	@test -n "$(DIR)$(HFS)" || (echo "Pass DIR=<path> and/or HFS=\"repo1 repo2\"" && exit 1)
	@HFFLAGS=""; for r in $(HFS); do HFFLAGS="$$HFFLAGS --hf $$r"; done; \
	 DIRFLAG=$$(if [ -n "$(DIR)" ]; then echo "--dir $(DIR)"; else echo ""; fi); \
	 . "$(CONVERT_VENV)/bin/activate" && \
	 python3 scripts/convert_batch.py $$DIRFLAG $$HFFLAGS \
	   --out-dir "$(OUTDIR)" --quantize $(QUANT) --jobs $(JOBS)

# ---------- publishing ----------

apk-info:
	@ls -lh $(debug_apk) $(release_apk) 2>/dev/null || true
	@echo "versionName: $(VERSION)"
	@echo "tag:         $(TAG)"
	@echo "gh repo:     $(GH_REPO)"

tag:
	@test -n "$(VERSION)" || (echo "Could not parse versionName from app/build.gradle.kts" && exit 1)
	git tag -a $(TAG) -m "Release $(TAG)"
	@echo "Tag created. Push with: git push origin $(TAG)"

# Builds a release APK and uploads it as an asset on a GitHub release.
# Requires the gh CLI (https://cli.github.com/) to be logged in.
# Override VERSION/TAG on the command line to republish a specific version.
publish: release
	@command -v gh >/dev/null 2>&1 || (echo "gh CLI not found. Install from https://cli.github.com/" && exit 1)
	@APK="$$(ls app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk 2>/dev/null | head -n1)"; \
	if [ -z "$$APK" ]; then echo "Release APK missing in app/build/outputs/apk/release/"; exit 1; fi; \
	case "$$APK" in \
	  *-unsigned.apk) echo "WARNING: uploading UNSIGNED apk ($$APK) — Android will refuse to install it."; \
	    echo "         set ANDROID_KEYSTORE_PATH (and *_PASSWORD / *_ALIAS) before 'make release' to sign."; ;; \
	esac; \
	if ! gh release view $(TAG) >/dev/null 2>&1; then \
	  echo "Creating release $(TAG)"; \
	  gh release create $(TAG) --title "$(TAG)" --notes "Release $(TAG)" || exit 1; \
	fi; \
	echo "Uploading $$APK to $(TAG)"; \
	gh release upload $(TAG) "$$APK" --clobber

publish-draft: release
	@command -v gh >/dev/null 2>&1 || (echo "gh CLI not found. Install from https://cli.github.com/" && exit 1)
	@APK="$$(ls app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk 2>/dev/null | head -n1)"; \
	if [ -z "$$APK" ]; then echo "Release APK missing in app/build/outputs/apk/release/"; exit 1; fi; \
	if ! gh release view $(TAG) >/dev/null 2>&1; then \
	  gh release create $(TAG) --draft --title "$(TAG)" --notes "Release $(TAG)" || exit 1; \
	fi; \
	gh release upload $(TAG) "$$APK" --clobber
