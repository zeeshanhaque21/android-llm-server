#!/usr/bin/env python3
"""Batch-convert a directory of GGUFs (or a list of HF repos) to .litertlm.

Spawns multiple `convert_to_litertlm.py` workers in parallel. Each worker
takes ~18 GB of resident memory and pegs CPU during the ai-edge-torch
trace + quantization pass, so concurrency is RAM-bounded — pick `--jobs`
based on `free -h`, not core count.

Usage:

    # Walk a directory, convert every .gguf with a supported architecture
    ./scripts/convert_batch.py --dir ~/Downloads --jobs 2

    # Explicit list of HF repos
    ./scripts/convert_batch.py \
        --hf Qwen/Qwen2.5-1.5B-Instruct \
        --hf google/gemma-2-2b-it \
        --hf microsoft/Phi-3.5-mini-instruct \
        --jobs 2

    # Mixed input — directory of GGUFs plus a few HF repos
    ./scripts/convert_batch.py --dir ~/Downloads --hf microsoft/Phi-3.5-mini-instruct

Output filenames are derived from the input: `<basename>.litertlm` next to
the GGUF, or in `--out-dir` if specified.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import importlib.util
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable

# Reuse the GGUF metadata helpers from the per-model script so we don't
# duplicate the ELF parser. Path-based load avoids needing the script
# directory to be on PYTHONPATH.
_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location(
    "convert_to_litertlm", _HERE / "convert_to_litertlm.py"
)
_conv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_conv)
read_gguf_metadata   = _conv.read_gguf_metadata
detect_arch_from_gguf = _conv.detect_arch_from_gguf
detect_source_from_gguf = _conv.detect_source_from_gguf
ARCH_TABLE = _conv.ARCH_TABLE


def log(msg: str) -> None:
    print(f"[batch] {msg}", flush=True)


def discover_ggufs(directory: Path) -> list[Path]:
    return sorted(p for p in directory.glob("*.gguf") if p.is_file())


def classify(gguf: Path) -> tuple[str | None, str | None, str | None]:
    """Return (hf_repo, arch, reason_skipped). reason_skipped is None on success."""
    try:
        meta = read_gguf_metadata(gguf)
    except Exception as e:  # noqa: BLE001 — broad on purpose; we want to skip not crash
        return None, None, f"could not parse: {e}"
    arch = detect_arch_from_gguf(meta)
    if not arch:
        return None, None, "no general.architecture in metadata"
    if arch not in ARCH_TABLE:
        return None, None, f"unsupported architecture '{arch}' (not in ai-edge-torch examples)"
    repo = detect_source_from_gguf(meta)
    if not repo:
        # Fall back to constructing a guess from basename + size_label.
        base = meta.get("general.basename", "").strip()
        size = meta.get("general.size_label", "").strip()
        if base and size:
            # This is a GUESS — many HF repos use the convention <Org>/<Base>-<Size>-Instruct.
            # We don't try to be clever; force the user to pass --hf if it doesn't exist.
            return None, arch, (
                f"no source repo in metadata. Best guess: '{base}-{size}-Instruct'. "
                f"Re-run with --hf <correct-repo> instead of relying on auto-detect."
            )
        return None, arch, "no source repo in metadata; pass --hf explicitly"
    return repo, arch, None


def run_one(repo: str, arch: str, variant: str | None, out: Path, quantize: str,
            workdir: Path | None, threads_per_worker: int) -> tuple[str, int, str]:
    """Run a single conversion. Returns (label, exit_code, last_lines)."""
    label = f"{repo} -> {out.name}"
    cmd = [
        sys.executable, str(_HERE / "convert_to_litertlm.py"),
        "--hf", repo, "--arch", arch,
        "--out", str(out), "--quantize", quantize,
    ]
    if variant:
        cmd += ["--variant", variant]
    if workdir:
        cmd += ["--workdir", str(workdir)]
    # Pin threads so parallel workers don't oversubscribe the CPU.
    # torch / TF / OpenBLAS / MKL all read these env vars.
    env = os.environ.copy()
    for k in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS",
              "MKL_NUM_THREADS", "TF_NUM_INTRAOP_THREADS",
              "TF_NUM_INTEROP_THREADS"):
        env[k] = str(threads_per_worker)
    log(f"start: {label} (threads={threads_per_worker})")
    t0 = time.monotonic()
    p = subprocess.run(cmd, capture_output=True, text=True, env=env)
    dur = time.monotonic() - t0
    tail = "\n".join((p.stdout + p.stderr).splitlines()[-15:])
    if p.returncode == 0:
        log(f"done : {label} in {dur:.0f}s")
    else:
        log(f"FAIL : {label} after {dur:.0f}s (exit {p.returncode})")
    return label, p.returncode, tail


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--dir", type=Path,
                   help="Directory to scan for *.gguf files.")
    p.add_argument("--hf", action="append", default=[],
                   help="HuggingFace repo to convert (may repeat).")
    p.add_argument("--out-dir", type=Path, default=Path.home() / "Downloads",
                   help="Output dir for .litertlm files (default: ~/Downloads).")
    p.add_argument("--quantize", default="dynamic_int8")
    p.add_argument("--jobs", "-j", type=int, default=2,
                   help="Concurrent conversions. Each needs ~18 GB RAM; "
                        "default 2 is safe on 54 GB machines.")
    p.add_argument("--workdir-root", type=Path,
                   help="If given, each worker uses a subdir here instead of /tmp.")
    args = p.parse_args(argv)

    args.out_dir.mkdir(parents=True, exist_ok=True)

    # Build the (repo, arch, out) work list.
    jobs: list[tuple[str, str, Path]] = []
    skipped: list[tuple[str, str]] = []

    import re
    # Try longer arch keys first so 'tiny_llama' wins over 'llama' for
    # 'TinyLlama/TinyLlama-1.1B-Chat-v1.0'.
    arch_keys_desc = sorted(ARCH_TABLE.keys(), key=len, reverse=True)

    def infer_variant(repo: str, arch: str) -> str | None:
        """Pick a variant from the repo name (e.g. '1.5B' -> '1.5b').

        Snaps to whatever ARCH_TABLE[arch]['variants'] declares — picks the
        closest match by parameter count when an exact match isn't found.
        """
        variants = ARCH_TABLE[arch]["variants"]
        m = re.search(r"(\d+(?:[._]\d+)?)\s*b", repo, re.IGNORECASE)
        if not m:
            return None
        size = m.group(1).replace("_", ".") + "b"
        if size.lower() in [v.lower() for v in variants]:
            return next(v for v in variants if v.lower() == size.lower())
        # No exact match — return None so caller defaults to first variant
        # and we surface a warning rather than guessing wrong.
        return None

    for raw in args.hf:
        # Allow `repo@variant` to override variant inference.
        if "@" in raw:
            repo, variant_override = raw.split("@", 1)
        else:
            repo, variant_override = raw, None

        rl = repo.lower().replace("-", "").replace("_", "")
        arch_hint = next(
            (k for k in arch_keys_desc if k.replace("_", "") in rl),
            None,
        )
        if not arch_hint:
            skipped.append((repo, "couldn't infer architecture from repo name; "
                            "use convert_to_litertlm.py directly with --arch"))
            continue
        variant = variant_override or infer_variant(repo, arch_hint)
        out = args.out_dir / (repo.replace("/", "_") + ".litertlm")
        jobs.append((repo, arch_hint, variant, out))

    if args.dir:
        for gguf in discover_ggufs(args.dir):
            repo, arch, why = classify(gguf)
            if why:
                skipped.append((str(gguf), why))
                continue
            out = args.out_dir / (gguf.stem + ".litertlm")
            jobs.append((repo, arch, None, out))  # let per-model script default variant

    if not jobs and not skipped:
        log("no models found. Pass --dir <path> or --hf <repo>.")
        return 2

    log(f"queued {len(jobs)} job(s); skipping {len(skipped)} input(s).")
    for path, why in skipped:
        log(f"  skip: {path}  ({why})")
    for repo, arch, variant, out in jobs:
        v = variant or "(default)"
        log(f"  plan: {repo}  ({arch}/{v}) -> {out}")

    if not jobs:
        return 1

    # Pin each worker to ~half the cores when JOBS>1 so torch/TF don't
    # oversubscribe the CPU. With JOBS=1, give the worker everything.
    cores = os.cpu_count() or 8
    threads_per_worker = max(1, cores // max(1, args.jobs))
    log(f"using {args.jobs} parallel worker(s), {threads_per_worker} threads each "
        f"(host has {cores} cores)")

    # Spawn workers. Concurrency is capped by --jobs; each gets a unique
    # workdir under --workdir-root if provided.
    results: list[tuple[str, int, str]] = []
    with cf.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = []
        for i, (repo, arch, variant, out) in enumerate(jobs):
            wd = (args.workdir_root / f"worker-{i}") if args.workdir_root else None
            if wd is not None:
                wd.mkdir(parents=True, exist_ok=True)
            futures.append(pool.submit(run_one, repo, arch, variant, out,
                                       args.quantize, wd, threads_per_worker))
        for fut in cf.as_completed(futures):
            results.append(fut.result())

    ok = sum(1 for _, rc, _ in results if rc == 0)
    failed = [r for r in results if r[1] != 0]
    log(f"finished: {ok}/{len(results)} succeeded.")
    for label, rc, tail in failed:
        log(f"--- failure tail: {label} (exit {rc}) ---")
        for line in tail.splitlines():
            log(f"  {line}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
