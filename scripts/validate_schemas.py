"""Validate every JSON under sources/ and tests/fixtures/ against schemas/.

Usage:
    python scripts/validate_schemas.py
Exit nonzero on the first failure group, printing all errors.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "schemas"

# filename suffix -> schema file
FIXTURE_SCHEMA_MAP = {
    ".source.json": "source.schema.json",
    ".story.json": "story.schema.json",
    ".edition.json": "edition.schema.json",
    ".index.json": "index.schema.json",
    ".ai-brief.json": "ai-brief.schema.json",
}


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def validator_for(schema_path: Path):
    schema = load_json(schema_path)
    cls = jsonschema.validators.validator_for(schema)
    cls.check_schema(schema)
    return cls(schema)


def main() -> int:
    errors: list[str] = []

    # 1. taxonomy.json structural check (not a JSON Schema)
    try:
        taxonomy = load_json(SCHEMAS / "taxonomy.json")
        ids = [c["id"] for c in taxonomy.get("categories", [])]
        expected = ["local", "public-safety", "business", "technology", "science",
                    "sports", "entertainment", "politics", "health",
                    "environment", "travel", "weather"]
        if ids != expected:
            errors.append(f"schemas/taxonomy.json: categories mismatch: {ids}")
        if taxonomy.get("apiVersion") != 1:
            errors.append("schemas/taxonomy.json: apiVersion must be 1")
    except Exception as exc:  # noqa: BLE001
        errors.append(f"schemas/taxonomy.json: {exc}")

    # 2. every file under sources/ must validate as a source entry,
    #    except regions.json files (metro definitions, validated structurally).
    source_validator = validator_for(SCHEMAS / "source.schema.json")
    seen_ids: set[str] = set()
    for path in sorted((ROOT / "sources").rglob("*.json")):
        if path.name == "regions.json":
            try:
                regions = load_json(path)
                if not isinstance(regions.get("regions"), list):
                    errors.append(f"{path}: regions.json needs a 'regions' list")
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{path}: {exc}")
            continue
        try:
            doc = load_json(path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path}: invalid JSON: {exc}")
            continue
        for err in source_validator.iter_errors(doc):
            errors.append(f"{path}: {err.message} ({'/'.join(map(str, err.path))})")
        sid = doc.get("id") if isinstance(doc, dict) else None
        if sid:
            if sid in seen_ids:
                errors.append(f"{path}: duplicate source id '{sid}'")
            seen_ids.add(sid)
        # keep the file layout geographic: sources/<global|us/...>/*.json
        rel = path.relative_to(ROOT / "sources").as_posix()
        if not (rel.startswith("global/") or rel.startswith("us/")):
            errors.append(f"{path}: must live under sources/global/ or sources/us/")

    # 3. fixtures self-check by suffix convention
    validators = {s: validator_for(SCHEMAS / s) for s in set(FIXTURE_SCHEMA_MAP.values())}
    for path in sorted((ROOT / "tests" / "fixtures").rglob("*.json")):
        suffix = next((s for s in FIXTURE_SCHEMA_MAP if path.name.endswith(s)), None)
        if suffix is None:
            continue  # e.g. raw gdelt samples, xml fixtures
        try:
            doc = load_json(path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path}: invalid JSON: {exc}")
            continue
        for err in validators[FIXTURE_SCHEMA_MAP[suffix]].iter_errors(doc):
            errors.append(f"{path}: {err.message} ({'/'.join(map(str, err.path))})")

    if errors:
        print(f"{len(errors)} schema validation error(s):")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(seen_ids)} source(s) + fixtures validate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
