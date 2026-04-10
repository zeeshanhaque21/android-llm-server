#!/usr/bin/env bash
# Runs once after the devcontainer is built. Safe to re-run.
set -euo pipefail

echo "[post-create] installing Android SDK packages (visible progress)..."
if command -v sdkmanager >/dev/null 2>&1; then
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"
else
  # First install: fetch cmdline-tools, then loop back for packages.
  ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
  sudo mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp=$(mktemp -d)
  (cd "$tmp" && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O ct.zip && unzip -q ct.zip)
  sudo mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  sudo chown -R "$USER:$USER" "$ANDROID_HOME"
  rm -rf "$tmp"
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"
fi

# Generate the Gradle wrapper if the repo doesn't have one yet. This is
# the one thing Android Studio would have done on first project sync —
# after this, ./gradlew assembleDebug works directly.
if [ -f "$PWD/settings.gradle.kts" ] && [ ! -x "$PWD/gradlew" ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "[post-create] generating Gradle wrapper (8.10)..."
    (cd "$PWD" && gradle wrapper --gradle-version 8.10 --distribution-type bin)
  else
    echo "[post-create] WARN: 'gradle' not on PATH — cannot bootstrap wrapper"
  fi
fi

echo "[post-create] installing Claude Code CLI (visible progress)..."
if ! command -v claude >/dev/null 2>&1; then
  sudo npm install -g --loglevel=http --no-audit --no-fund @anthropic-ai/claude-code
else
  echo "[post-create] claude already installed: $(claude --version 2>/dev/null || echo '?')"
fi

echo "[post-create] configuring shell conveniences..."

# Persist a shortcut so `yolo` launches Claude with skip-permissions.
# The container itself is the sandbox (nothing outside this project is
# mounted), so --dangerously-skip-permissions is the intended mode here.
SHELL_RC="${HOME}/.zshrc"
[ -f "$SHELL_RC" ] || SHELL_RC="${HOME}/.bashrc"

if ! grep -q "alias yolo=" "$SHELL_RC" 2>/dev/null; then
  cat >> "$SHELL_RC" <<'EOF'

# --- android-llm-server devcontainer ---
alias yolo='claude --dangerously-skip-permissions'
export PATH="$HOME/.local/bin:$PATH"

# claude-mem: host install points a shell alias at a bun-run .cjs inside
# the plugin cache. We recreate that alias here so `claude-mem` works
# inside the container. We resolve the newest version dir dynamically so
# plugin upgrades on the host are picked up automatically.
if [ -d "$HOME/.claude/plugins/cache/thedotmack/claude-mem" ]; then
  _cm_latest=$(ls -1v "$HOME/.claude/plugins/cache/thedotmack/claude-mem" 2>/dev/null | tail -n1)
  if [ -n "$_cm_latest" ]; then
    alias claude-mem="bun $HOME/.claude/plugins/cache/thedotmack/claude-mem/$_cm_latest/scripts/worker-service.cjs"
  fi
  unset _cm_latest
fi
EOF
fi

# Sanity check: confirm Claude can see the bind-mounted credentials.
if [ -f "$HOME/.claude.json" ] && [ -d "$HOME/.claude" ]; then
  echo "[post-create] Claude state mounted: $(ls -1 $HOME/.claude | wc -l) entries in ~/.claude"
  if [ -d "$HOME/.claude/plugins" ]; then
    echo "[post-create] plugins visible: $(ls -1 $HOME/.claude/plugins 2>/dev/null | wc -l) entries"
  fi
else
  echo "[post-create] WARNING: ~/.claude or ~/.claude.json not mounted — check devcontainer.json mounts"
fi

# claude-mem ships a node_modules with native addons (tree-sitter)
# that were built on the host. Rebuild them against the container's
# node ABI so the mcp-search MCP server can actually start. We do this
# only if a native .node file is present and appears to be broken.
CM_ROOT=$(ls -1vd "$HOME/.claude/plugins/cache/thedotmack/claude-mem"/*/ 2>/dev/null | tail -n1)
if [ -n "$CM_ROOT" ] && [ -d "$CM_ROOT/node_modules" ]; then
  if ! node -e "require('$CM_ROOT/node_modules/tree-sitter')" >/dev/null 2>&1; then
    echo "[post-create] rebuilding claude-mem native deps against container node ABI..."
    (cd "$CM_ROOT" && npm rebuild --silent) || echo "[post-create] WARN: claude-mem rebuild failed — mcp-search may not start"
  else
    echo "[post-create] claude-mem native deps load OK"
  fi
fi

# Plugin tooling sanity: warn if expected CLIs are missing.
for bin in gh clangd uvx bun rg jq gradle adb sdkmanager; do
  if command -v "$bin" >/dev/null 2>&1; then
    echo "[post-create]   ✓ $bin"
  else
    echo "[post-create]   ✗ $bin (some plugins may not work)"
  fi
done

# Android SDK sanity.
if command -v sdkmanager >/dev/null 2>&1; then
  echo "[post-create] Android SDK: $(sdkmanager --version 2>/dev/null || echo 'unknown')"
fi

echo "[post-create] done. Run 'yolo' to start Claude with --dangerously-skip-permissions."
