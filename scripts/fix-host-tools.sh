#!/usr/bin/env bash
# Strips .note.gnu.property from llama.cpp host build tools.
#
# Why: llama.cpp's vulkan-shaders-gen links against CRT objects that
# carry a bogus GNU_PROPERTY_X86_ISA_1_NEEDED=v4 marker on some distros
# (notably CachyOS with its aggressive toolchain). glibc's dynamic linker
# then refuses to exec the binary with "CPU ISA level is lower than
# required", even though the binary only uses baseline x86-64 ops.
#
# Dropping the property note is safe here: readelf shows "x86 ISA used:
# x86-64-baseline" — the NEEDED marker is pure over-reporting from the
# link stage. We only touch host tools, not any android/arm64 artifact.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cxx="$root/app/.cxx"

if [ ! -d "$cxx" ]; then
  exit 0
fi

if ! command -v objcopy >/dev/null 2>&1; then
  echo "[fix-host-tools] objcopy not on PATH — skipping" >&2
  exit 0
fi

fixed=0
while IFS= read -r -d '' bin; do
  # Only x86/x86_64 ELFs; skip anything else (arm target binaries).
  if file -b "$bin" 2>/dev/null | grep -q 'x86-64'; then
    if readelf -n "$bin" 2>/dev/null | grep -q "x86 ISA needed"; then
      objcopy --remove-section=.note.gnu.property "$bin"
      echo "[fix-host-tools] stripped $bin"
      fixed=$((fixed+1))
    fi
  fi
done < <(find "$cxx" -type f -name "vulkan-shaders-gen" -print0)

if [ "$fixed" -eq 0 ]; then
  echo "[fix-host-tools] nothing to fix"
fi
