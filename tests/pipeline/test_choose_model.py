"""Model-choice tests: queue-size threshold picks the run model (offline)."""
import json

from pipeline.ai import FALLBACK_MODEL, PRIMARY_MODEL
from pipeline.newsroom import choose_model


def _cfg(threshold=50):
    return {"primaryModel": PRIMARY_MODEL, "fallbackModel": FALLBACK_MODEL,
            "overflowThreshold": threshold}


def test_fifty_queued_stays_on_primary():
    assert choose_model(50, _cfg()) == PRIMARY_MODEL


def test_fifty_one_queued_switches_to_fallback():
    # Always primary for quality (4B 5/8 vs 1.7B 1/30); overflow disabled.
    assert choose_model(51, _cfg()) == PRIMARY_MODEL


def test_zero_queued_stays_on_primary():
    assert choose_model(0, _cfg()) == PRIMARY_MODEL


def test_explicit_override_always_wins():
    assert choose_model(51, _cfg(), override=PRIMARY_MODEL) == PRIMARY_MODEL
    assert choose_model(0, _cfg(), override="custom-model") == "custom-model"


def test_custom_threshold_respected():
    # Threshold is kept for manifest only; model choice always uses primary.
    assert choose_model(10, _cfg(threshold=10)) == PRIMARY_MODEL
    assert choose_model(11, _cfg(threshold=10)) == PRIMARY_MODEL


def _member(i, sid):
    return {
        "id": f"m{i}",
        "sourceId": sid,
        "publisher": f"Publisher {sid}",
        "headline": "Central Avenue fire in Albany",
        "excerpt": (
            "Firefighters responded to a blaze on Central Avenue in Albany. "
            "Crews closed the street while they worked the scene with officials on site."
        ),
        "url": f"https://example.com/{sid}/{i}",
        "publishedAt": "2026-09-23T09:05:00Z",
        "rightsMode": "RSS_EXCERPT_ALLOWED",
    }


def _cluster(eid, score=0.5):
    members = [_member(1, f"{eid}-a"), _member(2, f"{eid}-b")]
    return {
        "eventId": eid,
        "members": members,
        "memberIds": [m["id"] for m in members],
        "aliases": [],
        "firstSeen": "2026-09-23T09:05:00Z",
        "lastSeen": "2026-09-23T09:40:00Z",
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "section": "local",
        "category": "local",
        "headline": members[0]["headline"],
        "score": score,
        "scoreComponents": {},
        "breaking": False,
        "confidence": "HIGH",
        "confidenceTier": "high",
        "independentSources": 2,
        "sources": [],
        "status": "new",
        "version": 1,
    }


def _write_clusters(path, clusters):
    path.write_text(json.dumps({"generatedAt": "2026-09-23T12:00:00Z",
                                "clusters": clusters}), encoding="utf-8")


def test_choose_model_cli_writes_files_without_server(tmp_path):
    # 51 queued -> always primary (quality over throughput; 4 runs/day carry over).
    from pipeline import newsroom as nr

    clusters = [_cluster(f"eid-{i:04d}", score=1.0 - i * 0.001) for i in range(51)]
    in_path = tmp_path / "clusters.json"
    _write_clusters(in_path, clusters)
    choice = tmp_path / "model-choice.txt"
    queue_file = tmp_path / "queue.json"
    rc = nr.main(["--choose-model", "--in", str(in_path),
                  "--model-choice-file", str(choice),
                  "--queue-file", str(queue_file),
                  "--llama-url", "http://127.0.0.1:9"])
    assert rc == 0
    assert choice.read_text(encoding="utf-8").strip() == PRIMARY_MODEL
    manifest = json.loads(queue_file.read_text(encoding="utf-8"))
    assert manifest["model"] == PRIMARY_MODEL
    assert manifest["threshold"] == 9999
    assert len(manifest["queued"]) == 51
    # Rank order: best score first.
    assert manifest["queued"][0] == "eid-0000"


def test_choose_model_cli_small_queue_picks_primary(tmp_path):
    from pipeline import newsroom as nr

    clusters = [_cluster("eid-aaaa-0001", score=0.9), _cluster("eid-bbbb-0002", score=0.8)]
    in_path = tmp_path / "clusters.json"
    _write_clusters(in_path, clusters)
    choice = tmp_path / "model-choice.txt"
    queue_file = tmp_path / "queue.json"
    rc = nr.main(["--choose-model", "--in", str(in_path),
                  "--model-choice-file", str(choice),
                  "--queue-file", str(queue_file),
                  "--llama-url", "http://127.0.0.1:9"])
    assert rc == 0
    assert choice.read_text(encoding="utf-8").strip() == PRIMARY_MODEL
    manifest = json.loads(queue_file.read_text(encoding="utf-8"))
    assert manifest["queued"] == ["eid-aaaa-0001", "eid-bbbb-0002"]
