"""Ingest CLI: fetch all enabled sources, normalize, write items JSON.

    python -m pipeline.ingest [--out FILE] [--sources-dir DIR] [--state FILE]
        [--summary-file PATH]

One failing source never fails the run. Prints structured counters and, when
--summary-file is given, appends a source-health summary there. The pipeline
itself knows nothing about CI systems: a workflow passes its own summary
path as a plain CLI argument.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_state(path: Path) -> dict:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}
    return {}


def main(argv: list[str] | None = None) -> int:
    from .config import load_config
    from .normalize import normalize_item
    from .providers import ProviderError, fetch_with_state, get_provider, load_sources
    from .providers.http import build_client
    from .rights import is_ingestible

    parser = argparse.ArgumentParser(prog="pipeline.ingest")
    parser.add_argument("--out", default="state/normalized.json")
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    parser.add_argument("--state", default="state/http-state.json")
    parser.add_argument("--config", default=None)
    parser.add_argument(
        "--summary-file",
        default=None,
        help="Append a Markdown source-health summary to PATH (e.g. a CI step summary).",
    )
    args = parser.parse_args(argv)

    cfg = load_config(args.config)
    features = cfg.get("features", {})
    sources = [s for s in load_sources(Path(args.sources_dir)) if s.get("enabled")]
    if not features.get("enableGdelt", True):
        sources = [s for s in sources if s.get("type") != "gdelt"]
    if not features.get("enableNwsAlerts", True):
        sources = [s for s in sources if s.get("type") != "nws-alerts"]

    state_path = Path(args.state)
    http_state: dict = _load_state(state_path).get("http", {})

    client = build_client(cfg["userAgentContact"], cfg["requestTimeoutSeconds"])
    deps = {"client": client, "max_body_bytes": cfg["maxBodyBytes"]}

    now = datetime.now(timezone.utc)
    items: list[dict] = []
    failures: list[str] = []
    checked = 0
    dropped_rights = 0
    discovered = 0
    try:
        for source in sources:
            sid = str(source["id"])
            if not is_ingestible(str(source.get("rightsMode"))):
                dropped_rights += 1  # BLOCKED: dropped at fetch
                continue
            checked += 1
            try:
                raw_items = fetch_with_state(get_provider(str(source["type"]), deps), source, http_state)
            except ProviderError as exc:
                failures.append(f"{sid}: {exc.kind}")
                continue
            for raw in raw_items:
                discovered += 1
                norm = normalize_item(raw, source, now=now)
                if norm is not None:
                    items.append(norm.to_dict())
    finally:
        client.close()

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(
            {"generatedAt": now.isoformat().replace("+00:00", "Z"), "items": items},
            indent=2,
        ),
        encoding="utf-8",
    )
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps({"http": http_state}, indent=2), encoding="utf-8")

    print(
        f"sources checked={checked} failed={len(failures)} "
        f"items discovered={discovered} normalized={len(items)} "
        f"dropped-by-rights={dropped_rights}"
    )
    for f in failures:
        print(f"  FAIL {f}")
    if checked == 0:
        # Nothing was fetchable (e.g. every enabled source is BLOCKED).
        # That is a config state, not a failure: log it and exit clean.
        print(
            f"no-op: nothing fetchable "
            f"({dropped_rights} source(s) skipped by rights mode)"
        )

    summary_path = args.summary_file
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as fh:
            fh.write("## Source health\n\n")
            fh.write(f"Checked {checked}, failed {len(failures)}, items {len(items)}.\n\n")
            for f in failures:
                fh.write(f"- FAIL {f}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
