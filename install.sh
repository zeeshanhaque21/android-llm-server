#!/usr/bin/env bash
# android-llm-server bootstrap installer.
#
# Usage (macOS, Linux, WSL):
#   curl -fsSL https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.sh | bash
#
# Env overrides (export before piping, or `VAR=... bash -c '<curl> | bash'`):
#   ALSER_DIR          target directory              (default: $HOME/android-llm-server)
#   ALSER_REPO         git url                       (default: https://github.com/zeeshanhaque21/android-llm-server.git)
#   ALSER_REF          branch or tag                 (default: main)
#   ALSER_SKIP_SETUP   set to 1 to clone only (skip toolchain install)

set -euo pipefail

REPO="${ALSER_REPO:-https://github.com/zeeshanhaque21/android-llm-server.git}"
REF="${ALSER_REF:-main}"
DIR="${ALSER_DIR:-$HOME/android-llm-server}"

log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || fail "git is required. Install it and retry."

if [ -d "$DIR/.git" ]; then
  log "Updating existing clone at $DIR"
  git -C "$DIR" fetch --depth 1 origin "$REF"
  git -C "$DIR" checkout "$REF"
  git -C "$DIR" reset --hard "origin/$REF" 2>/dev/null || true
elif [ -e "$DIR" ]; then
  fail "$DIR exists and is not a git clone. Move it aside or set ALSER_DIR."
else
  log "Cloning $REPO ($REF) into $DIR"
  git clone --depth 1 --branch "$REF" "$REPO" "$DIR"
fi

log "Syncing submodules"
git -C "$DIR" submodule update --init --recursive

if [ "${ALSER_SKIP_SETUP:-0}" = "1" ]; then
  log "ALSER_SKIP_SETUP=1 — skipping toolchain install"
  log "Next: cd $DIR && bash scripts/setup.sh && make build"
  exit 0
fi

log "Running scripts/setup.sh (installs JDK 17, Android SDK/NDK/CMake)"
bash "$DIR/scripts/setup.sh"

cat <<EOF

------------------------------------------------------------
Install complete.

Project:   $DIR
Build:     cd $DIR && make build
Device:    cd $DIR && make run        # adb install + launch
Publish:   cd $DIR && make publish    # upload APK to GitHub release
------------------------------------------------------------
EOF
