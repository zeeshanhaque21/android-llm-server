# Base: Microsoft devcontainer Java image. Ships with a `vscode` user at
# UID 1000 (matches the host user on most Linux setups) plus git, zsh,
# sudo, and common build tools.
FROM mcr.microsoft.com/devcontainers/java:17-bookworm

ARG DEBIAN_FRONTEND=noninteractive

# --- System packages ----------------------------------------------------
# build-essential, cmake, ninja:   needed for JNI + llama.cpp native build
# unzip, wget:                      Android SDK cmdline-tools installer
# ripgrep, fd-find, jq:             developer ergonomics, also used by Claude
# python3:                          llama.cpp scripts + gradle tooling
RUN apt-get update \
  && apt-get install -y --no-install-recommends \
       build-essential \
       cmake \
       ninja-build \
       unzip \
       wget \
       ripgrep \
       fd-find \
       jq \
       python3 \
       python3-pip \
       pipx \
       clangd \
       sqlite3 \
       gradle \
       zsh \
  && rm -rf /var/lib/apt/lists/*

# --- GitHub CLI (required by `github` and `code-review` plugins) --------
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
  && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends gh \
  && rm -rf /var/lib/apt/lists/*

# --- uv / uvx (used by many MCP servers to launch Python-based tools) --
# Installed system-wide so the vscode user can invoke `uvx` directly.
RUN curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin sh

# --- Bun (required by the claude-mem plugin, which ships a .cjs entry) -
RUN curl -fsSL https://bun.sh/install | env BUN_INSTALL=/usr/local bash \
  && ln -sf /usr/local/bin/bun /usr/local/bin/bunx

# --- Node.js (for Claude Code CLI) --------------------------------------
# The Java base image doesn't ship Node. Install via NodeSource LTS.
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
  && apt-get install -y --no-install-recommends nodejs \
  && rm -rf /var/lib/apt/lists/*

# --- Android SDK command-line tools -------------------------------------
# Installed to /opt/android-sdk. NDK is NOT installed by default — it's
# multi-GB and not every session needs it. Uncomment the sdkmanager line
# below (or run it inside the container later) to add the NDK.
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
  && cd /tmp \
  && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip \
  && unzip -q cmdline-tools.zip \
  && mv cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
  && rm cmdline-tools.zip \
  && yes | sdkmanager --licenses > /dev/null \
  && sdkmanager --install \
       "platform-tools" \
       "platforms;android-34" \
       "build-tools;34.0.0" \
  && chown -R vscode:vscode "${ANDROID_HOME}"

# NDK (uncomment when you need native builds; ~2 GB):
# RUN sdkmanager --install "ndk;26.1.10909125" && chown -R vscode:vscode "${ANDROID_HOME}"

# Claude Code CLI is installed in post-create.sh, not here. Installing
# it during `docker build` produces no progress output and looks hung
# for several minutes while npm fetches native deps. Deferring to
# post-create makes the install visible and interruptible.

# --- Recreate the host user (same name + UID) ---------------------------
# We mirror the host username and UID so that $HOME inside the container
# is literally /home/zeeshan, matching the host. This makes any absolute
# path written to Claude state (plugin hooks, marketplace registrations,
# session files) resolve identically on both sides of the mount.
ARG HOST_USER=zeeshan
ARG HOST_UID=1000
ARG HOST_GID=1000

# The base image already ships a `vscode` user at UID 1000. Rename it
# (and its primary group + home) to ${HOST_USER} rather than creating a
# second UID-1000 account, which useradd refuses.
RUN set -eux; \
    existing_user="$(getent passwd "${HOST_UID}" | cut -d: -f1 || true)"; \
    existing_group="$(getent group  "${HOST_GID}" | cut -d: -f1 || true)"; \
    if [ -n "$existing_user" ] && [ "$existing_user" != "${HOST_USER}" ]; then \
      usermod  -l "${HOST_USER}" "$existing_user"; \
      usermod  -d "/home/${HOST_USER}" -m "${HOST_USER}"; \
    elif [ -z "$existing_user" ]; then \
      useradd  -m -u "${HOST_UID}" -g "${HOST_GID}" -s /bin/zsh "${HOST_USER}"; \
    fi; \
    if [ -n "$existing_group" ] && [ "$existing_group" != "${HOST_USER}" ]; then \
      groupmod -n "${HOST_USER}" "$existing_group" || true; \
    fi; \
    usermod  -s /bin/zsh "${HOST_USER}"; \
    chown -R "${HOST_UID}:${HOST_GID}" "/home/${HOST_USER}"; \
    rm -f  /etc/sudoers.d/vscode; \
    echo "${HOST_USER} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${HOST_USER}; \
    chmod 0440 /etc/sudoers.d/${HOST_USER}

USER ${HOST_USER}
WORKDIR /home/${HOST_USER}
