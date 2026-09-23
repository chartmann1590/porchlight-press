"""Registry CLI.

    python -m pipeline.sources validate   # schema + duplicate IDs + modes
    python -m pipeline.sources check <id> # fetch one source, print counts
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_schema(name: str) -> dict:
    import jsonschema

    schema = json.loads((ROOT / "schemas" / name).read_text(encoding="utf-8"))
    jsonschema.validators.validator_for(schema).check_schema(schema)
    return schema


def cmd_validate(sources_dir: Path) -> int:
    import jsonschema

    schema = _load_schema("source.schema.json")
    validator = jsonschema.validators.validator_for(schema)(schema)
    from .health import check_registry
    from .providers import load_sources

    sources = load_sources(sources_dir)
    errors: list[str] = []
    for path in sorted(sources_dir.rglob("*.json")):
        if path.name == "regions.json":
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        for err in validator.iter_errors(doc):
            errors.append(f"{path.name}: {err.message}")
    errors.extend(check_registry(sources))
    if errors:
        print(f"{len(errors)} registry error(s):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(sources)} source(s) validate.")
    return 0


def cmd_check(source_id: str, sources_dir: Path) -> int:
    from .config import load_config
    from .providers import ProviderError, get_provider, load_sources
    from .providers.http import build_client

    cfg = load_config()
    sources = {s["id"]: s for s in load_sources(sources_dir)}
    if source_id not in sources:
        print(f"unknown source id '{source_id}'", file=sys.stderr)
        return 2
    source = sources[source_id]
    client = build_client(cfg["userAgentContact"], cfg["requestTimeoutSeconds"])
    try:
        provider = get_provider(source["type"], {
            "client": client,
            "max_body_bytes": cfg["maxBodyBytes"],
        })
        items = provider.fetch(source)
    except ProviderError as exc:
        print(f"FAIL {exc.kind}: {exc.detail}")
        return 1
    finally:
        client.close()
    print(f"OK {source_id}: {len(items)} item(s)")
    for item in items[:5]:
        print(f"  - {item.title[:100]}")
    if len(items) > 5:
        print(f"  ... and {len(items) - 5} more")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pipeline.sources")
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("validate")
    check_p = sub.add_parser("check")
    check_p.add_argument("id")
    args = parser.parse_args(argv)
    sources_dir = Path(args.sources_dir)
    if args.cmd == "validate":
        return cmd_validate(sources_dir)
    return cmd_check(args.id, sources_dir)


if __name__ == "__main__":
    sys.exit(main())
