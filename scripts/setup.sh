#!/usr/bin/env bash
# Bootstraps the bare-minimum toolchain needed to build android-llm-server
# on Debian/Ubuntu, Arch, or macOS. Idempotent: re-running only installs
# what's missing. For Windows, use scripts/setup.ps1 from PowerShell.
set -euo pipefail

ANDROID_HOME_DEFAULT="$HOME/Android/Sdk"
CMDLINE_TOOLS_VERSION="11076708"  # pin — bump when Google publishes a new bundle
PLATFORM_VERSION="35"
BUILD_TOOLS_VERSION="35.0.0"
NDK_VERSION="26.1.10909125"
CMAKE_VERSION="3.22.1"

log()  { printf '\033[1;34m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# True only if a JDK 17 install can be found on standard paths.
# `java -version` from PATH is not enough — it may point at JDK 24+ which
# Gradle 8.10 refuses to launch on.
have_jdk17() {
  local c
  for c in \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-arm64 \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/temurin-17-jdk-arm64 \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  do
    [ -x "$c/bin/java" ] && return 0
  done
  [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 17 >/dev/null 2>&1 && return 0
  return 1
}

detect_os() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    Linux)
      if [ -r /etc/os-release ]; then
        . /etc/os-release
        case "${ID:-}${ID_LIKE:-}" in
          *debian*|*ubuntu*) echo "debian" ;;
          *arch*)            echo "arch"   ;;
          *) fail "Unsupported Linux distro: ${PRETTY_NAME:-unknown}. Install deps manually and re-run." ;;
        esac
      else
        fail "Cannot detect Linux distribution (/etc/os-release missing)."
      fi
      ;;
    *) fail "Unsupported OS: $(uname -s). Use scripts/setup.ps1 on Windows." ;;
  esac
}

sudo_if_needed() {
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi
}

install_pkgs_debian() {
  local pkgs=()
  have git   || pkgs+=(git)
  have curl  || pkgs+=(curl)
  have wget  || pkgs+=(wget)
  have unzip || pkgs+=(unzip)
  have_jdk17 || pkgs+=(openjdk-17-jdk-headless)
  have make  || pkgs+=(make)
  if [ "${#pkgs[@]}" -gt 0 ]; then
    log "apt install: ${pkgs[*]}"
    sudo_if_needed apt-get update
    sudo_if_needed apt-get install -y "${pkgs[@]}"
  fi
}

install_pkgs_arch() {
  local pkgs=()
  have git   || pkgs+=(git)
  have curl  || pkgs+=(curl)
  have wget  || pkgs+=(wget)
  have unzip || pkgs+=(unzip)
  have_jdk17 || pkgs+=(jdk17-openjdk)
  have make  || pkgs+=(make)
  if [ "${#pkgs[@]}" -gt 0 ]; then
    log "pacman -S: ${pkgs[*]}"
    sudo_if_needed pacman -Sy --needed --noconfirm "${pkgs[@]}"
  fi
}

install_pkgs_macos() {
  if ! have brew; then
    fail "Homebrew not found. Install it from https://brew.sh/ and re-run."
  fi
  local pkgs=()
  have git   || pkgs+=(git)
  have wget  || pkgs+=(wget)
  have_jdk17 || pkgs+=(openjdk@17)
  have make  || pkgs+=(make)
  if [ "${#pkgs[@]}" -gt 0 ]; then
    log "brew install: ${pkgs[*]}"
    brew install "${pkgs[@]}"
  fi
  # macOS doesn't expose Homebrew's openjdk on PATH for the Java wrappers by default.
  if [ -d "/opt/homebrew/opt/openjdk@17" ] && [ ! -L "/Library/Java/JavaVirtualMachines/openjdk-17.jdk" ]; then
    warn "If 'java -version' still fails, run:"
    warn "  sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
  fi
}

cmdline_tools_url() {
  case "$1" in
    debian|arch) echo "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" ;;
    macos)       echo "https://dl.google.com/android/repository/commandlinetools-mac-${CMDLINE_TOOLS_VERSION}_latest.zip" ;;
    *) fail "No cmdline-tools URL for $1" ;;
  esac
}

install_android_sdk() {
  local os="$1"
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME_DEFAULT}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"

  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    log "Installing Android command-line tools to $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    local tmp
    tmp="$(mktemp -d)"
    local url
    url="$(cmdline_tools_url "$os")"
    log "Downloading $url"
    curl -fsSL "$url" -o "$tmp/ct.zip"
    unzip -q "$tmp/ct.zip" -d "$tmp"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
  else
    log "Android cmdline-tools already present at $ANDROID_HOME"
  fi

  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  log "Accepting SDK licenses"
  yes | "$sdkmanager" --licenses >/dev/null || true

  log "Installing SDK packages (platform-$PLATFORM_VERSION, build-tools $BUILD_TOOLS_VERSION, NDK $NDK_VERSION, CMake $CMAKE_VERSION)"
  "$sdkmanager" --install \
    "platform-tools" \
    "platforms;android-${PLATFORM_VERSION}" \
    "build-tools;${BUILD_TOOLS_VERSION}" \
    "ndk;${NDK_VERSION}" \
    "cmake;${CMAKE_VERSION}"
}

write_local_properties() {
  local root="$1"
  local lp="$root/local.properties"
  if [ ! -f "$lp" ] || ! grep -q '^sdk.dir=' "$lp" 2>/dev/null; then
    log "Writing $lp"
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$lp"
  fi
}

sync_submodules() {
  local root="$1"
  if [ ! -f "$root/.gitmodules" ]; then
    return
  fi
  log "Syncing git submodules (llama.cpp, stable-diffusion.cpp)"
  git -C "$root" submodule update --init --recursive
}

# Locate a JDK 17 install. Gradle 8.10 refuses to launch on newer JVMs
# (e.g. JDK 26), and this project targets Java 17 bytecode anyway, so
# JAVA_HOME must point at 17. We try distro-standard paths first, then
# macOS's java_home helper, then Homebrew.
find_jdk17() {
  local c
  for c in \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-arm64 \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/temurin-17-jdk-arm64 \
    /usr/lib/jvm/adoptium-17-jdk-amd64
  do
    if [ -x "$c/bin/java" ] && "$c/bin/java" -version 2>&1 | grep -qE '"17\.|version "?17'; then
      echo "$c"; return 0
    fi
  done
  if [ -x /usr/libexec/java_home ]; then
    local h
    h="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -n "$h" ] && { echo "$h"; return 0; }
  fi
  for c in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  do
    [ -x "$c/bin/java" ] && { echo "$c"; return 0; }
  done
  return 1
}

SHELL_BLOCK_MARKER="# >>> android-llm-server env >>>"
SHELL_BLOCK_END="# <<< android-llm-server env <<<"

# Pick the rc file(s) to update based on the user's login shell. We write
# to the file that matches $SHELL; if that can't be determined, we fall
# back to updating every rc file we find so the next shell session has
# the exports regardless.
rc_files_for_shell() {
  local shell_name
  shell_name="$(basename "${SHELL:-}")"
  case "$shell_name" in
    zsh)  echo "$HOME/.zshrc" ;;
    bash)
      # macOS login shells source .bash_profile, Linux typically .bashrc.
      if [ "$(uname -s)" = "Darwin" ]; then echo "$HOME/.bash_profile"
      else                                   echo "$HOME/.bashrc"
      fi
      ;;
    fish) echo "$HOME/.config/fish/config.fish" ;;
    *)
      # Unknown shell — update anything that exists so at least one works.
      for f in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile"; do
        [ -f "$f" ] && echo "$f"
      done
      ;;
  esac
}

# Remove any existing managed block from $1 (in-place). No-op if absent.
strip_existing_block() {
  local rc="$1"
  [ -f "$rc" ] || return 0
  grep -Fq "$SHELL_BLOCK_MARKER" "$rc" || return 0
  local tmp
  tmp="$(mktemp)"
  awk -v s="$SHELL_BLOCK_MARKER" -v e="$SHELL_BLOCK_END" '
    $0==s { inblk=1; next }
    inblk && $0==e { inblk=0; next }
    !inblk { print }
  ' "$rc" > "$tmp"
  # Drop trailing blank lines left by the strip.
  sed -i -e :a -e '/^$/{$d;N;ba' -e '}' "$tmp" 2>/dev/null || true
  mv "$tmp" "$rc"
}

update_shell_rc() {
  local jdk17
  jdk17="$(find_jdk17 || true)"
  if [ -z "$jdk17" ]; then
    warn "JDK 17 not found on standard paths — Gradle may fail to launch."
    warn "Install JDK 17 and re-run this script, or set JAVA_HOME manually."
  fi

  local rc
  for rc in $(rc_files_for_shell); do
    # Create the rc if the user's shell expects one but it's absent.
    if [ ! -e "$rc" ]; then
      mkdir -p "$(dirname "$rc")"
      touch "$rc"
    fi

    # Always rewrite the managed block so re-runs pick up new values
    # (e.g. JAVA_HOME added after a fresh JDK 17 install).
    if grep -Fq "$SHELL_BLOCK_MARKER" "$rc" 2>/dev/null; then
      log "Refreshing env block in $rc"
      strip_existing_block "$rc"
    else
      log "Appending env block to $rc"
    fi
    case "$rc" in
      *config.fish)
        {
          printf '\n%s\n' "$SHELL_BLOCK_MARKER"
          printf 'set -gx ANDROID_HOME "%s"\n' "$ANDROID_HOME"
          printf 'set -gx ANDROID_SDK_ROOT "$ANDROID_HOME"\n'
          if [ -n "$jdk17" ]; then
            printf 'set -gx JAVA_HOME "%s"\n' "$jdk17"
            printf 'fish_add_path "$JAVA_HOME/bin" "$ANDROID_HOME/cmdline-tools/latest/bin" "$ANDROID_HOME/platform-tools"\n'
          else
            printf 'fish_add_path "$ANDROID_HOME/cmdline-tools/latest/bin" "$ANDROID_HOME/platform-tools"\n'
          fi
          printf '%s\n' "$SHELL_BLOCK_END"
        } >> "$rc"
        ;;
      *)
        {
          printf '\n%s\n' "$SHELL_BLOCK_MARKER"
          printf 'export ANDROID_HOME="%s"\n' "$ANDROID_HOME"
          printf 'export ANDROID_SDK_ROOT="$ANDROID_HOME"\n'
          if [ -n "$jdk17" ]; then
            printf 'export JAVA_HOME="%s"\n' "$jdk17"
          fi
          cat <<'EOF'
_alser_prepend() {
  case ":$PATH:" in *":$1:"*) ;; *) PATH="$1:$PATH" ;; esac
}
[ -n "${JAVA_HOME:-}" ] && _alser_prepend "$JAVA_HOME/bin"
_alser_prepend "$ANDROID_HOME/platform-tools"
_alser_prepend "$ANDROID_HOME/cmdline-tools/latest/bin"
export PATH
unset -f _alser_prepend
EOF
          printf '%s\n' "$SHELL_BLOCK_END"
        } >> "$rc"
        ;;
    esac
  done
}

print_done_hint() {
  cat <<EOF

------------------------------------------------------------
Setup complete.

Open a new terminal (or \`source\` your shell rc) to pick up the new
JAVA_HOME, ANDROID_HOME, and PATH entries.

Next steps:
  make build
------------------------------------------------------------
EOF
}

main() {
  local repo_root
  repo_root="$(cd "$(dirname "$0")/.." && pwd)"
  local os
  os="$(detect_os)"
  log "Detected OS: $os"

  case "$os" in
    debian) install_pkgs_debian ;;
    arch)   install_pkgs_arch   ;;
    macos)  install_pkgs_macos  ;;
  esac

  install_android_sdk "$os"
  write_local_properties "$repo_root"
  sync_submodules "$repo_root"
  update_shell_rc
  print_done_hint
}

main "$@"
