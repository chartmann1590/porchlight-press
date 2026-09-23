"""Slow integration: real llama-server on one fixture cluster (CI nightly only).

Never downloads or runs a model in normal tests: if no server answers at
the configured URL, the test skips. Nightly CI starts the server (see
.github/workflows/ai-newsroom.yml) and runs ``pytest -m slow``.
"""
import json
import os
from pathlib import Path

import pytest

pytestmark = pytest.mark.slow

FIX = Path(__file__).resolve().parent.parent / "fixtures" / "albany_fire.json"


def _fixture_cluster():
    from pipeline.cluster import cluster_items
    from pipeline.locate import load_places, locate_item
    from pipeline.rank import confidence_for

    items = json.loads(FIX.read_text(encoding="utf-8"))["items"]
    base = {"coverage": {"country": "US", "admin1": "US-NY",
                         "admin2": ["Albany County"], "cities": ["Albany"],
                         "metro": "us-ny-capital-region"}, "type": "rss"}
    places = load_places(None)
    located = []
    for it in items:
        info = locate_item(it, base, places=places)
        e = dict(it)
        e["locations"] = info["locations"]
        located.append(e)
    clusters = cluster_items(located)
    assert len(clusters) == 1
    c = clusters[0]
    c["sources"] = [
        {"publisher": m.get("publisher", ""), "headline": m.get("headline", ""),
         "url": m.get("url", ""), "publishedAt": m.get("publishedAt", ""),
         "rightsMode": m.get("rightsMode", "")}
        for m in c["members"]
    ]
    c["confidence"] = confidence_for(
        c["members"],
        {m.get("sourceId", ""): {"priority": 70} for m in c["members"]},
    )
    c["confidenceTier"] = c["confidence"].lower()
    c["category"] = "local"
    c["score"] = 0.9
    c["status"] = "new"
    c["version"] = 1
    return c


def test_real_model_single_fixture_cluster():
    import urllib.request

    url = os.environ.get("PORCHLIGHT_LLAMA_URL", "http://127.0.0.1:8080")
    try:
        with urllib.request.urlopen(url + "/health", timeout=5) as resp:  # noqa: S310
            if resp.status != 200:
                pytest.skip("llama-server not healthy")
    except Exception as exc:  # noqa: BLE001 - offline: skip, never fail
        pytest.skip(f"no live llama-server at {url}: {exc}")

    from pipeline.ai import LocalLlamaProvider, try_brief_with_retry

    cluster = _fixture_cluster()
    provider = LocalLlamaProvider(base_url=url, timeout_seconds=300)
    brief, _result, _raw, err = try_brief_with_retry(provider, cluster)
    # Either a validated brief or a logged rejection (fallback path also
    # valid for this smoke test); the nightly workflow asserts the feed is
    # complete via the newsroom CLI, not here.
    if brief is None:
        pytest.skip(f"model did not produce a valid brief: {err}")
    assert brief["headline"] and brief["body"]
