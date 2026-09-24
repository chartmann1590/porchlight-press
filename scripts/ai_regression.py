"""AI newsroom regression: real model over real clusters, per-fixture verdicts.

    python scripts/ai_regression.py [--fixtures tests/fixtures/ai_regression]
        [--llama-url http://127.0.0.1:8080] [--model Qwen3-4B-Q4_K_M]

Loads every cluster JSON in the fixtures dir (each file is one cluster, as
saved from the pipeline-state branch), runs try_brief_with_retry against a
live llama-server, validates deterministically, and prints per-fixture
accept/reject + timing plus a summary acceptance rate.

Offline (no server): prints NOT-RUN for every fixture and exits 2 so the
caller can distinguish "no model" from "model rejected everything". Never
downloads a model; never writes outside state/ except stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="scripts.ai_regression")
    parser.add_argument("--fixtures", default="tests/fixtures/ai_regression")
    parser.add_argument("--llama-url", default="http://127.0.0.1:8080")
    parser.add_argument("--model", default=None)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    args = parser.parse_args(argv)

    # Robust when run as `python scripts/ai_regression.py` (sys.path[0] is
    # scripts/, not the repo root): ensure the root is importable.
    from pathlib import Path as _Path

    _root = _Path(__file__).resolve().parent.parent
    if str(_root) not in sys.path:
        sys.path.insert(0, str(_root))

    from pipeline.ai import LocalLlamaProvider, try_brief_with_retry
    from pipeline.ai.validate import validate_brief

    fix_dir = Path(args.fixtures)
    files = sorted(fix_dir.glob("*.json"))
    if not files:
        print(f"no fixtures in {fix_dir}", file=sys.stderr)
        return 2

    # Server check first: offline must not look like 0% acceptance.
    import urllib.request

    try:
        with urllib.request.urlopen(args.llama_url.rstrip("/") + "/health", timeout=5) as resp:  # noqa: S310
            if resp.status != 200:
                print(f"llama-server not healthy at {args.llama_url}", file=sys.stderr)
                return 2
    except Exception as exc:  # noqa: BLE001 - offline: NOT-RUN, never fail
        for f in files:
            print(f"NOT-RUN {f.stem}: no live llama-server at {args.llama_url} ({exc})")
        print("summary: NOT-RUN (no server)")
        return 2

    provider = LocalLlamaProvider(
        base_url=args.llama_url,
        model_name=args.model or "Qwen3-4B-Q4_K_M",
        timeout_seconds=args.timeout_seconds,
    )
    n_ok = 0
    total_s = 0.0
    for f in files:
        cluster = json.loads(f.read_text(encoding="utf-8"))
        t0 = time.monotonic()
        brief, result, _raw, err = try_brief_with_retry(provider, cluster)
        dt = time.monotonic() - t0
        total_s += dt
        if brief is not None and result is not None and result.ok:
            n_ok += 1
            words = len(str(brief.get("body") or "").split())
            print(f"ACCEPT {f.stem}: ok words={words} {dt:.1f}s")
        else:
            # Re-validate for a clean reason line when the provider gave up.
            reasons = "; ".join((result.reasons if result else []) or [err or "unknown"])[:220]
            # Double-check with a fresh validation when a brief exists but failed.
            if brief is not None:
                vr = validate_brief(brief, cluster)
                if vr.reasons:
                    reasons = "; ".join(vr.reasons)[:220]
            print(f"REJECT {f.stem}: {reasons} {dt:.1f}s")
    rate = n_ok / len(files) * 100.0
    avg = total_s / len(files) if files else 0.0
    print(f"summary: {n_ok}/{len(files)} accepted ({rate:.0f}%), avg {avg:.1f}s/fixture, total {total_s:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
