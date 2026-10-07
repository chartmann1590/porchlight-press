"""Process end-to-end: fixture batch -> clusters with IDs, locations, scores."""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

FIX = Path(__file__).resolve().parent.parent / "fixtures" / "albany_fire.json"


def _iso(dt: datetime) -> str:
    return dt.isoformat().replace("+00:00", "Z")


def _fixture_relative_to_now() -> str:
    """The fixture's timestamps, shifted so its newest item was published an hour ago.

    The pipeline compares item times against the real clock, so fixed dates
    eventually age out of the dedupe/retention windows and the test starts failing.
    """
    data = json.loads(FIX.read_text(encoding="utf-8"))
    parse = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00"))
    newest = max(parse(i["publishedAt"]) for i in data["items"])
    shift = (datetime.now(timezone.utc) - timedelta(hours=1)) - newest
    for item in data["items"]:
        item["publishedAt"] = _iso(parse(item["publishedAt"]) + shift)
    if data.get("generatedAt"):
        data["generatedAt"] = _iso(parse(data["generatedAt"]) + shift)
    return json.dumps(data)


def _write_sources(srcdir: Path):
    srcdir.mkdir(parents=True, exist_ok=True)
    defs = {
        "wnyt-newschannel-13": ("WNYT", 80, "rss"),
        "cbs6-albany": ("CBS6", 75, "rss"),
        "times-union-albany": ("Times Union", 70, "rss"),
    }
    for sid, (name, prio, typ) in defs.items():
        (srcdir / f"{sid}.json").write_text(json.dumps({
            "apiVersion": 1, "id": sid, "name": name,
            "homepage": "https://example.com/", "feedUrl": "https://example.com/feed",
            "type": typ, "coverage": {"country": "US", "admin1": "US-NY",
                                      "admin2": ["Albany County"], "cities": ["Albany"],
                                      "metro": "us-ny-capital-region"},
            "rightsMode": "RSS_EXCERPT_ALLOWED", "language": "en",
            "enabled": True, "priority": prio,
        }), encoding="utf-8")


def test_process_fixture_produces_ranked_located_clusters(tmp_path):
    from pipeline.process import main

    srcdir = tmp_path / "sources"
    _write_sources(srcdir)
    in_path = tmp_path / "normalized.json"
    in_path.write_text(_fixture_relative_to_now(), encoding="utf-8")
    state_path = tmp_path / "clusters.json"
    out_path = tmp_path / "out.json"

    rc = main(["--in", str(in_path), "--sources-dir", str(srcdir),
               "--state", str(state_path), "--out", str(out_path)])
    assert rc == 0
    payload = json.loads(out_path.read_text(encoding="utf-8"))
    clusters = payload["clusters"]
    assert len(clusters) == 1
    c = clusters[0]
    assert len(c["eventId"]) == 16
    assert c["locations"] and c["locations"][0]["city"] == "Albany"
    assert c["section"] == "local"
    assert c["score"] > 0 and c["confidence"] in ("HIGH", "MEDIUM", "LOW", "UNVERIFIED")
    assert c["status"] in ("new", "updated", "unchanged")
    assert c["memberIds"] and len(c["sources"]) == 3

    # Second run with identical input is unchanged (stable IDs).
    rc2 = main(["--in", str(in_path), "--sources-dir", str(srcdir),
                "--state", str(state_path), "--out", str(out_path)])
    assert rc2 == 0
    again = json.loads(out_path.read_text(encoding="utf-8"))["clusters"]
    assert again[0]["eventId"] == c["eventId"]
    assert again[0]["status"] == "unchanged"


def test_process_step_failure_is_one_line_and_nonzero(tmp_path, capsys):
    from pipeline.process import main

    srcdir = tmp_path / "sources"
    srcdir.mkdir(parents=True, exist_ok=True)
    bad = tmp_path / "bad.json"
    bad.write_text('{"items": "not-a-list"}', encoding="utf-8")
    rc = main(["--in", str(bad), "--sources-dir", str(srcdir),
               "--state", str(tmp_path / "s.json"),
               "--out", str(tmp_path / "o.json")])
    assert rc != 0
    err = capsys.readouterr().err.strip()
    assert err.startswith("process failed at step load:")
    assert "\n" not in err  # one line, no traceback


def test_coverage_fallback_is_worldwide_no_us_default():
    from pipeline.process import _coverage_fallback_location

    members = [{"sourceId": "jp-source"}]
    sources = {"jp-source": {"coverage": {"country": "JP"}}}
    assert _coverage_fallback_location(members, sources) == {"country": "JP"}
    # No coverage anywhere -> None (caller stores no location -> World).
    assert _coverage_fallback_location(members, {}) is None
    assert _coverage_fallback_location(
        [{"sourceId": "x"}], {"x": {"coverage": {}}}) is None
