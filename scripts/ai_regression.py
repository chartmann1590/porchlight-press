"""AI newsroom regression: real model over real clusters, per-fixture verdicts.

    python scripts/ai_regression.py [--fixtures tests/fixtures/ai_regression]
        [--llama-url http://127.0.0.1:8080] [--model Qwen3-4B-Q4_K_M]
        [--out state/regression-briefs.json]

Loads every cluster JSON in the fixtures dir (each file is one cluster, as
saved from the pipeline-state branch), runs try_brief_with_retry against a
live llama-server, validates deterministically, and prints per-fixture
accept/reject + timing (tries, tokens, tok/s when the server reports usage)
plus a summary acceptance rate. Accepted briefs are saved to --out for
quality review (the PR lists every accepted brief text).

Offline (no server): prints NOT-RUN for every fixture and exits 2 so the
caller can distinguish "no model" from "model rejected everything". Never
downloads a model.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path


class _CountingProvider:
    """Wraps LocalLlamaProvider to count generations per fixture (tries)."""

    def __init__(self, inner):
        self._inner = inner
        self.tries = 0

    def __getattr__(self, name):
        return getattr(self._inner, name)

    def generate(self, cluster):
        self.tries += 1
        return self._inner.generate(cluster)

    def generate_with_messages(self, messages, temperature=None):
        self.tries += 1
        return self._inner.generate_with_messages(messages, temperature=temperature)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="scripts.ai_regression")
    parser.add_argument("--fixtures", default="tests/fixtures/ai_regression")
    parser.add_argument("--llama-url", default="http://127.0.0.1:8080")
    parser.add_argument("--model", default=None)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--out", default=None,
                        help="Write accepted briefs {fixture: brief} as JSON for review.")
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

    inner = LocalLlamaProvider(
        base_url=args.llama_url,
        model_name=args.model or "Qwen3-4B-Q4_K_M",
        timeout_seconds=args.timeout_seconds,
    )
    provider = _CountingProvider(inner)
    n_ok = 0
    n_first_try = 0
    total_s = 0.0
    accepted: dict[str, dict] = {}
    for f in files:
        cluster = json.loads(f.read_text(encoding="utf-8"))
        provider.tries = 0
        inner.last_usage = {}
        t0 = time.monotonic()
        brief, result, _raw, err = try_brief_with_retry(provider, cluster)
        dt = time.monotonic() - t0
        total_s += dt
        tries = provider.tries
        usage = dict(getattr(inner, "last_usage", {}) or {})
        tok = ""
        if usage.get("completion_tokens") and dt > 0:
            tok = (f" tok={usage.get('prompt_tokens')}/{usage.get('completion_tokens')}"
                   f" {usage.get('completion_tokens') / dt:.1f}t/s")
        if brief is not None and result is not None and result.ok:
            n_ok += 1
            if tries <= 1:
                n_first_try += 1
            words = len(str(brief.get("body") or "").split())
            accepted[f.stem] = dict(brief)
            print(f"ACCEPT {f.stem}: ok words={words} tries={tries}{tok} {dt:.1f}s")
        else:
            # Re-validate for a clean reason line when the provider gave up.
            reasons = "; ".join((result.reasons if result else []) or [err or "unknown"])[:220]
            # Double-check with a fresh validation when a brief exists but failed.
            if brief is not None:
                vr = validate_brief(brief, cluster)
                if vr.reasons:
                    reasons = "; ".join(vr.reasons)[:220]
            print(f"REJECT {f.stem}: {reasons} tries={tries}{tok} {dt:.1f}s")
    rate = n_ok / len(files) * 100.0
    avg = total_s / len(files) if files else 0.0
    print(f"summary: {n_ok}/{len(files)} accepted ({rate:.0f}%), "
          f"first-try {n_first_try}/{len(files)}, avg {avg:.1f}s/fixture, total {total_s:.1f}s")
    if args.out and accepted:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(accepted, indent=2), encoding="utf-8")
        print(f"wrote {len(accepted)} accepted briefs to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
