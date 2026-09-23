"""Ranking tests: monotonicity, locality ladder, confidence, breaking."""
from datetime import datetime, timedelta, timezone

from pipeline.rank import (confidence_for, is_official_source, locality_score,
                           score_cluster)

NOW = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)


def _member(sid, published_at, rights="RSS_EXCERPT_ALLOWED", publisher=None):
    return {"sourceId": sid, "publisher": publisher or sid, "headline": "H",
            "url": f"https://example.com/{sid}", "publishedAt": published_at,
            "rightsMode": rights}


def _cluster(members, section="local", category="local"):
    return {"members": members, "section": section, "category": category}


SOURCES = {
    "trusted": {"id": "trusted", "priority": 80, "type": "rss", "rightsMode": "RSS_EXCERPT_ALLOWED"},
    "small": {"id": "small", "priority": 20, "type": "rss", "rightsMode": "RSS_EXCERPT_ALLOWED"},
    "nws": {"id": "nws", "priority": 95, "type": "nws-alerts", "rightsMode": "PUBLIC_DOMAIN",
            "name": "National Weather Service"},
    "linky": {"id": "linky", "priority": 10, "type": "rss", "rightsMode": "LINK_ONLY"},
}


def test_ranking_monotonicity_fresher_ge_older():
    fresh = _cluster([_member("trusted", "2026-09-23T11:00:00Z")])
    old = _cluster([_member("trusted", "2026-09-22T11:00:00Z")])
    assert score_cluster(fresh, SOURCES, now=NOW)["score"] >= score_cluster(old, SOURCES, now=NOW)["score"]


def test_locality_ladder_ordering():
    scores = {}
    for section in ("local", "regional", "state", "national", "world"):
        c = _cluster([_member("trusted", "2026-09-23T11:00:00Z")], section=section)
        scores[section] = score_cluster(c, SOURCES, now=NOW)["score"]
    assert scores["local"] > scores["regional"] > scores["state"] > scores["national"] > scores["world"]
    assert locality_score("local") > locality_score("regional") > locality_score("state")


def test_confidence_tiers():
    two = [_member("a", "2026-09-23T11:00:00Z"), _member("b", "2026-09-23T11:00:00Z")]
    assert confidence_for(two, {}) == "HIGH"
    official_mix = [_member("nws", "2026-09-23T11:00:00Z"), _member("trusted", "2026-09-23T11:05:00Z")]
    assert confidence_for(official_mix, SOURCES) == "HIGH"
    assert confidence_for([_member("trusted", "2026-09-23T11:00:00Z")], SOURCES) == "MEDIUM"
    assert confidence_for([_member("small", "2026-09-23T11:00:00Z")], SOURCES) == "LOW"
    assert confidence_for([_member("linky", "2026-09-23T11:00:00Z", rights="LINK_ONLY")], SOURCES) == "UNVERIFIED"


def test_breaking_national_can_top_local_solo():
    solo = _cluster([_member("trusted", "2026-09-23T11:50:00Z")],
                    section="local", category="local")
    breaking_members = [
        _member("a", "2026-09-23T11:00:00Z"),
        _member("b", "2026-09-23T11:20:00Z"),
        _member("c", "2026-09-23T11:40:00Z"),
    ]
    breaking = _cluster(breaking_members, section="national", category="politics")
    solo_sources = {"trusted": SOURCES["trusted"], "a": {"priority": 90},
                    "b": {"priority": 90}, "c": {"priority": 90}}
    assert score_cluster(breaking, solo_sources, now=NOW)["breaking"] is True
    assert score_cluster(breaking, solo_sources, now=NOW)["score"] > \
        score_cluster(solo, SOURCES, now=NOW)["score"]


def test_no_ideology_features():
    # Same priority, different publisher names -> identical scores.
    left = _cluster([_member("left-outlet", "2026-09-23T11:00:00Z")], category="politics")
    right = _cluster([_member("right-outlet", "2026-09-23T11:00:00Z")], category="politics")
    srcs = {"left-outlet": {"priority": 60}, "right-outlet": {"priority": 60}}
    assert score_cluster(left, srcs, now=NOW)["score"] == score_cluster(right, srcs, now=NOW)["score"]
    # No political-lean scoring: strip comments/docstrings, then ensure no
    # leaning vocabulary survives in executable code. (The module docstring
    # documents the ban, so it is excluded from the scan.)
    import re
    src_text = open("pipeline/rank.py", encoding="utf-8").read().lower()
    code_only = re.sub(r'""".*?"""', " ", src_text, flags=re.DOTALL)
    code_only = "\n".join(
        line for line in code_only.splitlines()
        if not line.strip().startswith("#")
    )
    for banned in ("left-wing", "right-wing", "liberal-bias", "conservative-bias",
                   "partisan", "lean-left", "lean-right", "endorsement",
                   "vote for", "party ranking"):
        assert banned not in code_only


def test_official_detection():
    assert is_official_source(SOURCES["nws"])
    assert not is_official_source(SOURCES["trusted"])
    assert not is_official_source(None)


def test_stale_penalty_grows():
    recent = _cluster([_member("trusted", (NOW - timedelta(hours=10)).isoformat())])
    stale = _cluster([_member("trusted", (NOW - timedelta(hours=60)).isoformat())])
    assert score_cluster(recent, SOURCES, now=NOW)["score"] > \
        score_cluster(stale, SOURCES, now=NOW)["score"]
