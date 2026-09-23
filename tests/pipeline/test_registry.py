"""Registry validation tests: seeds pass, duplicates/unknown modes fail."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def test_seed_registry_validates_and_has_test_market():
    from pipeline.health import check_registry
    from pipeline.providers import load_sources

    sources = load_sources(ROOT / "sources")
    assert check_registry(sources) == []
    ids = {s["id"] for s in sources}
    assert {"wamc-northeast-report", "wnyt-newschannel-13", "wten-news10",
            "nws-albany-alerts", "npr-news", "usgs-earthquakes",
            "bbc-world", "gdelt-doc-discovery"} <= ids
    # every committed seed URL was verified live before committing
    for src in sources:
        assert src.get("feedUrl") or src.get("apiUrl")
        assert src.get("lastVerified")


def test_duplicate_ids_detected():
    from pipeline.health import check_registry

    src = {"id": "dup", "rightsMode": "RSS_EXCERPT_ALLOWED"}
    problems = check_registry([src, dict(src)])
    assert any("duplicate" in p for p in problems)


def test_unknown_rights_mode_detected():
    from pipeline.health import check_registry

    problems = check_registry([{"id": "x", "rightsMode": "YOLO"}])
    assert any("unknown rightsMode" in p for p in problems)


def test_seed_files_match_source_schema():
    import jsonschema

    schema = json.loads((ROOT / "schemas" / "source.schema.json").read_text())
    validator = jsonschema.validators.validator_for(schema)(schema)
    from pipeline.providers import load_sources

    for src in load_sources(ROOT / "sources"):
        assert list(validator.iter_errors(src)) == []
