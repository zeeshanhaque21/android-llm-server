#!/usr/bin/env python3
"""Convert a HuggingFace transformer (or a GGUF derived from one) to .litertlm.

There is **no direct GGUF -> .litertlm path**. Google's pipeline is:

    HuggingFace PyTorch checkpoint
        -> litert_torch.generative.examples.<arch>.convert_to_tflite
        -> .tflite (per signature: prefill, decode, embedder)
        -> litert-lm-builder bundles them with a tokenizer + metadata
        -> .litertlm (ready for sideloading to Android)

This script wraps both stages. For GGUF inputs we don't dequantize; we read
the embedded `general.basename`/`general.name`/`general.source.huggingface.repository`
metadata to figure out the source HF repo and pull THAT, which is the same
weights the GGUF was quantized from.

Only the architectures that ai-edge-torch's Generative API supports work
(gemma/gemma3/llama/phi/qwen/qwen_vl/smollm/tiny_llama/falcon/openelm/...).
A random fine-tune of a custom architecture won't convert.

Usage examples:

    # From a HuggingFace repo (preferred)
    ./scripts/convert_to_litertlm.py \
        --hf meta-llama/Llama-3.2-1B-Instruct \
        --arch llama --variant 1b \
        --out ~/llama32-1b.litertlm

    # From a local GGUF (script discovers source HF repo from metadata)
    ./scripts/convert_to_litertlm.py \
        --gguf ~/models/qwen2.5-1.5b-instruct-q4_k_m.gguf \
        --out ~/qwen.litertlm

Heavy machine recommended (~16 GB RAM, ~10-30 min per model). On-device
conversion is not supported by either ai-edge-torch or LiteRT-LM-builder.
"""

from __future__ import annotations

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# Architecture map. Keys are the values you'd pass via --arch (or that we
# detect from GGUF/HF metadata). Each entry maps to the convert_to_tflite.py
# module path inside the installed `ai-edge-torch` (a.k.a. litert-torch)
# package and the variants it supports.
# Updated 2026-04: keep in sync with
# https://github.com/google-ai-edge/litert-torch/tree/main/litert_torch/generative/examples
# ---------------------------------------------------------------------------
ARCH_TABLE: dict[str, dict] = {
    "gemma":     {"module": "litert_torch.generative.examples.gemma.convert_to_tflite",     "variants": ["2b", "7b"]},
    "gemma3":    {"module": "litert_torch.generative.examples.gemma3.convert_to_tflite",    "variants": ["1b", "4b"]},
    "llama":     {"module": "litert_torch.generative.examples.llama.convert_to_tflite",     "variants": ["1b", "3b"]},
    "phi":       {"module": "litert_torch.generative.examples.phi.convert_to_tflite",       "variants": ["3", "3.5", "4_mini"]},
    "qwen":      {"module": "litert_torch.generative.examples.qwen.convert_to_tflite",      "variants": ["0.5b", "1.5b", "3b"]},
    "qwen_vl":   {"module": "litert_torch.generative.examples.qwen_vl.convert_to_tflite",   "variants": ["3b"]},
    "smollm":    {"module": "litert_torch.generative.examples.smollm.convert_to_tflite",    "variants": ["135m", "360m"]},
    "tiny_llama":{"module": "litert_torch.generative.examples.tiny_llama.convert_to_tflite","variants": ["1.1b"]},
    "falcon":    {"module": "litert_torch.generative.examples.falcon.convert_to_tflite",    "variants": ["1b"]},
    "openelm":   {"module": "litert_torch.generative.examples.openelm.convert_to_tflite",   "variants": ["1.1b", "3b"]},
    "deepseek":  {"module": "litert_torch.generative.examples.deepseek.convert_to_tflite",  "variants": ["1.5b"]},
}

# Quantization recipes recognised by ai-edge-torch's converter. INT4
# variants are 4x smaller than INT8 and the smallest sensible default
# for on-device inference on a phone — quality drops are usually
# imperceptible on 1B–3B chat models.
QUANTIZE_CHOICES = [
    "dynamic_int4_block128",  # smallest, recommended for phones
    "dynamic_int4_block32",   # slightly larger, marginally better quality
    "dynamic_int8",
    "weight_only_int8",
    "fp16",
    "none",
]


def log(msg: str) -> None:
    print(f"[convert] {msg}", flush=True)


def fail(msg: str, code: int = 1) -> None:
    print(f"[error]   {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


# ---------------------------------------------------------------------------
# GGUF metadata reader. We only need the few keys that identify the source
# HuggingFace repo, so a tiny hand-rolled parser is cheaper than pulling in
# the `gguf` PyPI package as a dependency.
# ---------------------------------------------------------------------------

GGUF_TYPE_STRING = 8
GGUF_TYPE_UINT32 = 4


def read_gguf_metadata(path: Path) -> dict[str, str]:
    """Return the string-valued metadata entries from a GGUF file.

    Handles only the value types we actually care about (string + uint32
    for the entry count). Silently skips anything else.
    """
    meta: dict[str, str] = {}
    with path.open("rb") as f:
        magic = f.read(4)
        if magic != b"GGUF":
            return meta
        version = struct.unpack("<I", f.read(4))[0]
        _tensor_count = struct.unpack("<Q", f.read(8))[0]
        kv_count = struct.unpack("<Q", f.read(8))[0]

        def read_str() -> str:
            n = struct.unpack("<Q", f.read(8))[0]
            return f.read(n).decode("utf-8", errors="replace")

        def skip_value(vtype: int) -> None:
            # We only need to navigate past values we don't read. Cover the
            # primitive types; arrays are skipped by length-prefix.
            sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
            if vtype in sizes:
                f.read(sizes[vtype])
            elif vtype == GGUF_TYPE_STRING:
                read_str()
            elif vtype == 9:  # array
                inner = struct.unpack("<I", f.read(4))[0]
                n = struct.unpack("<Q", f.read(8))[0]
                for _ in range(n):
                    skip_value(inner)

        for _ in range(kv_count):
            key = read_str()
            vtype = struct.unpack("<I", f.read(4))[0]
            if vtype == GGUF_TYPE_STRING:
                meta[key] = read_str()
            else:
                skip_value(vtype)
    return meta


def detect_source_from_gguf(meta: dict[str, str]) -> Optional[str]:
    """Best-effort recovery of the HuggingFace repo a GGUF was built from."""
    for key in (
        "general.source.huggingface.repository",
        "general.source.url",
        "general.basename",
        "general.name",
    ):
        if key in meta:
            v = meta[key].strip()
            if "/" in v and not v.startswith("http"):
                return v
            if v.startswith("https://huggingface.co/"):
                return v.removeprefix("https://huggingface.co/").rstrip("/")
    return None


def detect_arch_from_gguf(meta: dict[str, str]) -> Optional[str]:
    arch = meta.get("general.architecture", "").lower()
    if not arch:
        return None
    # GGUF arch names map onto our table mostly 1:1, with some normalisation.
    # Qwen 1/2/2.5/3 GGUFs all report variant strings; ai-edge-torch's
    # generative.examples.qwen handles them via the same converter.
    aliases = {
        "gemma2": "gemma", "gemma_2": "gemma",
        "llama3": "llama",
        "qwen2": "qwen", "qwen2.5": "qwen", "qwen3": "qwen",
    }
    return aliases.get(arch, arch)


# ---------------------------------------------------------------------------
# Conversion driver
# ---------------------------------------------------------------------------

def ensure_tools_installed() -> None:
    """Verify litert-torch + litert-lm-builder are importable."""
    missing = []
    for mod in ("litert_torch", "litert_lm_builder"):
        if subprocess.call([sys.executable, "-c", f"import {mod}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL):
            missing.append(mod)
    if missing:
        fail(
            "Missing Python packages: " + ", ".join(missing) + "\n"
            "          Install with:\n"
            "              pip install ai-edge-torch litert-lm-builder huggingface_hub\n"
            "          (and a recent torch + transformers; see ai-edge-torch's pyproject)."
        )


def download_hf_checkpoint(repo: str, target_dir: Path, hf_token: Optional[str]) -> Path:
    """Mirror an HF model repo locally. Returns the local directory path."""
    log(f"downloading HF checkpoint {repo} -> {target_dir}")
    target_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, "-c",
        "import sys; from huggingface_hub import snapshot_download;"
        "snapshot_download(sys.argv[1], local_dir=sys.argv[2], token=sys.argv[3] or None)",
        repo, str(target_dir), hf_token or "",
    ]
    subprocess.check_call(cmd)
    return target_dir


def run_tflite_conversion(arch: str, variant: str, checkpoint_dir: Path,
                          out_dir: Path, name_prefix: str, quantize: str,
                          kv_cache_max_len: int, prefill_seq_lens: list[int]) -> list[Path]:
    """Invoke ai-edge-torch's per-architecture convert_to_tflite.py."""
    entry = ARCH_TABLE[arch]
    cmd = [
        sys.executable, "-m", entry["module"],
        f"--checkpoint_path={checkpoint_dir}",
        f"--output_path={out_dir}",
        f"--output_name_prefix={name_prefix}",
        f"--quantize={quantize}",
        f"--kv_cache_max_len={kv_cache_max_len}",
    ]
    # `--model_size` is only defined by converters that have multiple
    # variants (llama, qwen, gemma, etc.). Single-variant converters
    # (tiny_llama, deepseek, falcon's only-1b, qwen_vl's only-3b) reject
    # the flag with "FATAL Flags parsing error: Unknown command line flag".
    if len(entry["variants"]) > 1:
        cmd.append(f"--model_size={variant}")
    for n in prefill_seq_lens:
        cmd.append(f"--prefill_seq_lens={n}")
    log("running ai-edge-torch convert_to_tflite:")
    log("  " + " ".join(cmd))
    subprocess.check_call(cmd)
    produced = sorted(out_dir.glob(f"{name_prefix}*.tflite"))
    if not produced:
        fail("convert_to_tflite produced no .tflite files")
    return produced


def find_tokenizer(checkpoint_dir: Path) -> tuple[Path, str]:
    """Find the tokenizer in an HF checkpoint.

    Returns (path, kind) where kind is "sp" for SentencePiece (.model)
    or "hf" for HuggingFace tokenizer.json (BPE). Both are accepted by
    litert-lm-builder via the `sp_tokenizer` / `hf_tokenizer` subcommands.
    """
    for name in ("tokenizer.model", "spiece.model"):
        p = checkpoint_dir / name
        if p.exists():
            return p, "sp"
    p = checkpoint_dir / "tokenizer.json"
    if p.exists():
        return p, "hf"
    fail(
        f"No tokenizer found in {checkpoint_dir}. Looked for "
        f"tokenizer.model / spiece.model (SentencePiece) and "
        f"tokenizer.json (HuggingFace BPE)."
    )
    return Path(), ""  # unreachable


def run_litertlm_bundle(tflite_files: list[Path], tokenizer: Path,
                        tokenizer_kind: str,
                        out_path: Path, model_label: str) -> None:
    """Bundle .tflite + tokenizer into a .litertlm file via litert-lm-builder."""
    cmd = [
        sys.executable, "-m", "litert_lm_builder.litertlm_builder_cli",
        "system_metadata", "--str", "Authors", "android-llm-server convert_to_litertlm.py",
        "system_metadata", "--str", "Source", model_label,
    ]
    # The converter typically emits one prefill_decode .tflite. If multiple
    # variants land (e.g. embedder + prefill_decode) we wire them in order.
    for tf in tflite_files:
        kind = "embedder" if "embed" in tf.name.lower() else "prefill_decode"
        cmd += ["tflite_model", "--path", str(tf), "--model_type", kind]
    if tokenizer_kind == "sp":
        cmd += ["sp_tokenizer", "--path", str(tokenizer)]
    else:
        cmd += ["hf_tokenizer", "--path", str(tokenizer)]
    cmd += ["output", "--path", str(out_path)]
    log("running litert-lm-builder:")
    log("  " + " ".join(cmd))
    subprocess.check_call(cmd)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--hf", metavar="REPO", help="HuggingFace repo id (preferred)")
    src.add_argument("--gguf", metavar="PATH", type=Path,
                     help="Local GGUF file. Source HF repo is read from its metadata.")

    p.add_argument("--arch", choices=sorted(ARCH_TABLE.keys()),
                   help="Architecture family (auto-detected for GGUF).")
    p.add_argument("--variant", help="Variant within the family (e.g. '1b', '3b').")
    p.add_argument("--out", required=True, type=Path,
                   help="Output .litertlm path.")
    p.add_argument("--quantize", default="dynamic_int8", choices=QUANTIZE_CHOICES)
    p.add_argument("--kv-cache-max-len", type=int, default=1280)
    p.add_argument("--prefill-seq-lens", type=int, nargs="+",
                   default=[128, 1024],
                   help="Each value adds a separate prefill signature trace + "
                        "convert pass; conversion time scales linearly. The "
                        "default pair covers short prompts (128) and full-context "
                        "(1024). For fastest conversion at the cost of "
                        "less efficient prefill on intermediate lengths, use "
                        "just '--prefill-seq-lens 1024'.")
    p.add_argument("--workdir", type=Path,
                   help="Working directory for intermediate files (default: temp dir).")
    p.add_argument("--hf-token", help="HuggingFace access token "
                   "(falls back to $HF_TOKEN env var).")

    args = p.parse_args(argv)
    args.hf_token = args.hf_token or os.environ.get("HF_TOKEN")

    ensure_tools_installed()

    # Resolve source repo.
    if args.gguf:
        if not args.gguf.exists():
            fail(f"GGUF not found: {args.gguf}")
        log(f"reading GGUF metadata from {args.gguf}")
        meta = read_gguf_metadata(args.gguf)
        repo = detect_source_from_gguf(meta)
        if not repo:
            fail("Could not determine source HuggingFace repo from this GGUF's "
                 "metadata. Pass --hf <repo> directly.")
        log(f"detected source repo: {repo}")
        args.arch = args.arch or detect_arch_from_gguf(meta)
        if not args.arch:
            fail("Could not determine architecture from GGUF. Pass --arch.")
    else:
        repo = args.hf

    if args.arch not in ARCH_TABLE:
        fail(f"Unsupported architecture: {args.arch}\n"
             f"          Supported: {', '.join(sorted(ARCH_TABLE.keys()))}")
    variants = ARCH_TABLE[args.arch]["variants"]
    if not args.variant:
        # Pick the smallest variant by default (usually first in the list).
        args.variant = variants[0]
        log(f"--variant not given; defaulting to '{args.variant}' "
            f"(supported: {variants})")
    elif args.variant not in variants:
        fail(f"Unsupported {args.arch} variant '{args.variant}'. "
             f"Choose from: {variants}")

    # Working dir for the HF checkout + tflite intermediates.
    work_owned = args.workdir is None
    workdir = Path(args.workdir or tempfile.mkdtemp(prefix="convert-litertlm-"))
    workdir.mkdir(parents=True, exist_ok=True)
    log(f"workdir: {workdir}")

    try:
        ckpt_dir = workdir / "hf"
        download_hf_checkpoint(repo, ckpt_dir, args.hf_token)

        tflite_dir = workdir / "tflite"
        tflite_dir.mkdir(exist_ok=True)
        prefix = repo.replace("/", "_")
        tflite_files = run_tflite_conversion(
            arch=args.arch, variant=args.variant,
            checkpoint_dir=ckpt_dir, out_dir=tflite_dir, name_prefix=prefix,
            quantize=args.quantize, kv_cache_max_len=args.kv_cache_max_len,
            prefill_seq_lens=args.prefill_seq_lens,
        )

        tokenizer, tokenizer_kind = find_tokenizer(ckpt_dir)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        run_litertlm_bundle(tflite_files, tokenizer, tokenizer_kind, args.out,
                            model_label=f"{repo} ({args.arch}/{args.variant}, {args.quantize})")
        log(f"DONE. wrote {args.out} ({args.out.stat().st_size / 1e6:.1f} MB)")
        log("Push to phone with:  adb push "
            f"{args.out} /sdcard/Download/")
        succeeded = True
    finally:
        # Only nuke an auto-created workdir if everything succeeded —
        # the .tflite intermediates take many minutes to produce, and
        # if bundling failed we want them around for a hand-retry.
        if work_owned and locals().get("succeeded"):
            shutil.rmtree(workdir, ignore_errors=True)
        elif work_owned:
            log(f"keeping workdir {workdir} for inspection (delete manually)")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
