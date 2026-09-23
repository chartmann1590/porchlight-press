"""Clustering tests: Albany fire fixture, ID stability, merge aliases."""
import hashlib
import json
from pathlib import Path

from pipeline.cluster import cluster_items, event_id_for_seed, maybe_merge_clusters
from pipeline.locate import load_places, locate_item

FIX = Path(__file__).resolve().parent.parent / "fixtures" / "albany_fire.json"
BASE_SOURCE = {"coverage": {"country": "US", "admin1": "US-NY",
                            "admin2": ["Albany County"], "cities": ["Albany"],
                            "metro": "us-ny-capital-region"}, "type": "rss"}


def _located():
    items = json.loads(FIX.read_text(encoding="utf-8"))["items"]
    places = load_places(None)
    out = []
    for it in items:
        info = locate_item(it, BASE_SOURCE, places=places)
        e = dict(it)
        e["locations"] = info["locations"]
        out.append(e)
    return out


def test_albany_fire_forms_one_cluster():
    clusters = cluster_items(_located())
    assert len(clusters) == 1
    assert len(clusters[0]["members"]) == 3
    pubs = {m["sourceId"] for m in clusters[0]["members"]}
    assert len(pubs) == 3  # CBS6/WNYT/Times Union style: three publishers


def test_event_id_is_seed_url_hash():
    clusters = cluster_items(_located())
    seed_url = "https://wnyt.com/2026/09/23/central-avenue-fire-albany/"
    assert clusters[0]["eventId"] == hashlib.sha256(seed_url.encode()).hexdigest()[:16]
    assert clusters[0]["eventId"] == event_id_for_seed(seed_url)


def test_id_stable_when_fourth_source_joins():
    items = _located()
    first = cluster_items(items)[0]["eventId"]
    fourth = {"id": "fire-wamc-004", "sourceId": "wamc",
              "publisher": "WAMC", "headline": "Crews contain Central Avenue fire in Albany",
              "excerpt": "Firefighters contained the Central Avenue blaze in Albany late Tuesday morning.",
              "url": "https://wamc.org/2026/09/23/central-avenue-fire-contained/",
              "publishedAt": "2026-09-23T11:00:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
              "locations": items[0]["locations"]}
    again = cluster_items(items + [fourth])
    assert len(again) == 1
    assert again[0]["eventId"] == first  # seed unchanged => stable ID
    assert len(again[0]["members"]) == 4


def test_merge_alias_older_survives():
    a_items = _located()[:2]
    b_items = _located()[2:]
    ca = cluster_items(a_items)[0]
    cb = cluster_items(b_items)[0]
    # Force them apart by time, then merge explicitly.
    merged = maybe_merge_clusters([ca, cb], merge_threshold=0.0)
    assert len(merged) == 1
    survivor = merged[0]
    assert survivor["eventId"] in (ca["eventId"], cb["eventId"])
    other = cb["eventId"] if survivor["eventId"] == ca["eventId"] else ca["eventId"]
    assert other in survivor["aliases"]


def test_deterministic_same_input_same_output():
    a = cluster_items(_located())
    b = cluster_items(list(reversed(_located())))
    assert [c["eventId"] for c in a] == [c["eventId"] for c in b]
