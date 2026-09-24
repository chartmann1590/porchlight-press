"""Persisted-state revalidation: heal pre-#12 place-only merges + U+FFFD on load.

Regression guard for 0d0cf2f78345feea: the EXACT stored pair below is copied
from origin/pipeline-state:state/clusters.json (headlines, excerpts,
locations, publishedAt, urls, ids). The old gate merged it (shared entity
'ballston spa', ent=1.0, cos=0.052, loc=1.0 -> combined 0.454 >= 0.45);
the hardened gate requires a text floor with any shared entity and splits it.
"""
import json

from pipeline.state import (
    cluster_content_fingerprint,
    load_state,
    stored_brief_usable,
)

_BALLSTON_LOC = [{"country": "US", "admin1": "US-NY", "admin2": "Saratoga County",
                  "city": "Ballston Spa", "metro": "us-ny-capital-region"}]

# Exact stored member 1 of 0d0cf2f78345feea.
_REAL_AWARD = {
    "id": "e81d72c90f678d594683fc78ea04e4e8", "sourceId": "wten-news10",
    "publisher": "WTEN News10 ABC",
    "headline": "Ballston Spa HS student nominated for Heart of a Giant Award",
    "excerpt": ("USA Football\u2019s Heart of a Giant Award, presented by Hospital "
                "for Special Surgery and the New York Giants is now on! A local "
                "Ballston Spa student has been nominated."),
    "url": "https://www.news10.com/news/week-1-ballston-spa-hs-student-nominated-for-heart-of-a-giant-award/",
    "publishedAt": "2026-09-23T22:00:35Z", "language": "en",
    "rightsMode": "RSS_EXCERPT_ALLOWED", "imageCandidate": None, "rawLocations": [],
    "locations": [
        {"country": "US", "admin1": "US-NY"},
        {"country": "US", "admin1": "US-NY", "admin2": "Albany County",
         "city": "Albany", "metro": "us-ny-capital-region"},
    ],
}

# Exact stored member 2 of 0d0cf2f78345feea.
_REAL_CANCER = {
    "id": "2b337ec0e52732877445cbf011b86dff", "sourceId": "wamc-northeast-report",
    "publisher": "WAMC Northeast Public Radio",
    "headline": "A husband and father from Ballston Spa got cancer \u2014 his friends stepped up",
    "excerpt": ("After 39-year-old Nick Henry got diagnosed with a rare and aggressive "
                "cancer, his friends created an organization to support his family and other"),
    "url": "https://www.wamc.org/news/2026-09-24/the-miles-together-cancer-burnt-hills-cross-country-track-ballston-spa",
    "publishedAt": "2026-09-24T15:38:41Z", "language": "en",
    "rightsMode": "RSS_EXCERPT_ALLOWED", "imageCandidate": None, "rawLocations": [],
    "locations": [
        {"country": "US", "admin1": "US-NY", "admin2": "Albany County",
         "city": "Albany", "metro": "us-ny-capital-region"},
    ],
}


def _wten_award():
    return {
        "id": "wten-award-001", "sourceId": "wten", "publisher": "WTEN",
        "headline": "Ballston Spa HS student nominated for Heart of a Giant Award",
        "excerpt": ("A Ballston Spa High School student has been nominated for the "
                    "Heart of a Giant Award honoring high school football players."),
        "url": "https://wten.com/2026/09/23/ballston-spa-award/",
        "publishedAt": "2026-09-23T09:05:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _BALLSTON_LOC,
    }


def _wamc_cancer():
    return {
        "id": "wamc-cancer-002", "sourceId": "wamc", "publisher": "WAMC",
        "headline": "A husband and father from Ballston Spa got cancer \u2014 his friends stepped up",
        "excerpt": ("After a Ballston Spa father was diagnosed with cancer, "
                    "his friends rallied to support the family."),
        "url": "https://wamc.org/2026/09/23/ballston-spa-cancer-support/",
        "publishedAt": "2026-09-23T10:15:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _BALLSTON_LOC,
    }


def _persisted_record(event_id, members, headline=None, brief=None, locations=None):
    rec = {
        "eventId": event_id,
        "members": [dict(m) for m in members],
        "memberIds": [m["id"] for m in members],
        "aliases": [],
        "version": 2,
        "status": "unchanged",
        "firstSeen": members[0].get("publishedAt"),
        "lastSeen": members[-1].get("publishedAt"),
        "headline": headline if headline is not None else members[-1].get("headline", ""),
        "locations": locations if locations is not None else _BALLSTON_LOC,
        "sources": [
            {"sourceId": m.get("sourceId", ""), "publisher": m.get("publisher", ""),
             "headline": m.get("headline", ""), "url": m.get("url", ""),
             "publishedAt": m.get("publishedAt", ""), "rightsMode": m.get("rightsMode", "")}
            for m in members
        ],
    }
    if brief is not None:
        rec["lastBrief"] = dict(brief)
        rec["lastBriefFingerprint"] = cluster_content_fingerprint(rec)
        rec["lastBriefModel"] = "test-model"
        rec["lastBriefHash"] = "legacy"
        rec["lastGeneratedAt"] = "2026-09-23T11:00:00Z"
    else:
        rec["lastBrief"] = None
        rec["lastBriefFingerprint"] = None
        rec["lastBriefModel"] = None
        rec["lastBriefHash"] = None
        rec["lastGeneratedAt"] = None
    return rec


def _write_state(path, records_by_id):
    path.write_text(json.dumps({"generatedAt": "2026-09-23T12:00:00Z",
                                "clusters": records_by_id}), encoding="utf-8")


def _good_brief():
    return {"headline": "Ballston brief", "dek": "Dek here.",
            "body": "Body with enough words here for a brief."}


def test_real_ballston_pair_does_not_cluster():
    from pipeline.cluster import cluster_items, maybe_merge_clusters

    members = [dict(_REAL_AWARD), dict(_REAL_CANCER)]
    clusters = cluster_items(members)
    assert len(clusters) == 2
    assert {len(c["members"]) for c in clusters} == {1}
    ca = cluster_items([dict(_REAL_AWARD)])[0]
    cb = cluster_items([dict(_REAL_CANCER)])[0]
    assert len(maybe_merge_clusters([ca, cb])) == 2


def test_persisted_real_ballston_pair_splits_and_brief_not_reused(tmp_path):
    members = [dict(_REAL_AWARD), dict(_REAL_CANCER)]
    rec = _persisted_record(
        "0d0cf2f78345feea", members,
        headline=members[1]["headline"], brief=_good_brief(),
        locations=[
            {"country": "US", "admin1": "US-NY", "admin2": "Albany County",
             "city": "Albany", "metro": "us-ny-capital-region"},
            {"country": "US", "admin1": "US-NY"},
        ],
    )
    state_path = tmp_path / "clusters.json"
    _write_state(state_path, {"0d0cf2f78345feea": rec})

    loaded = load_state(state_path)
    # Split into two single-member clusters.
    assert len(loaded) == 2
    all_member_ids = sorted([mid for r in loaded.values() for mid in r.get("memberIds", [])])
    assert all_member_ids == sorted(["e81d72c90f678d594683fc78ea04e4e8",
                                     "2b337ec0e52732877445cbf011b86dff"])
    # Original eventId stays with anchor (earliest member = award).
    assert "0d0cf2f78345feea" in loaded
    assert loaded["0d0cf2f78345feea"]["memberIds"] == ["e81d72c90f678d594683fc78ea04e4e8"]
    # Split survivor must not reuse the old 2-member brief.
    assert not stored_brief_usable(loaded["0d0cf2f78345feea"])
    assert loaded["0d0cf2f78345feea"].get("lastBrief") is None
    # Split-off has no brief either.
    other_ids = [k for k in loaded if k != "0d0cf2f78345feea"]
    assert len(other_ids) == 1
    assert loaded[other_ids[0]]["memberIds"] == ["2b337ec0e52732877445cbf011b86dff"]
    assert not stored_brief_usable(loaded[other_ids[0]])
    # Fingerprint check from PR #11 already covers membership change:
    # old 2-member fingerprint differs from either 1-member fingerprint.
    old_fp = rec["lastBriefFingerprint"]
    for r in loaded.values():
        assert cluster_content_fingerprint(r) != old_fp


def test_persisted_fffd_is_scrubbed_and_brief_dropped(tmp_path):
    bad_headline = "A husband and father from Ballston Spa got cancer \ufffd his friends stepped up"
    m = _wamc_cancer()
    m = dict(m, headline=bad_headline, excerpt="Excerpt with \ufffd replacement.")
    rec = _persisted_record("fffd-0001", [m], headline=bad_headline,
                            brief={"headline": "Bad \ufffd brief", "dek": "D\ufffd",
                                   "body": "Body \ufffd here"})
    # Recompute fingerprint over the dirty content (as stored pre-fix).
    rec["lastBriefFingerprint"] = cluster_content_fingerprint(rec)
    state_path = tmp_path / "clusters.json"
    _write_state(state_path, {"fffd-0001": rec})

    loaded = load_state(state_path)
    assert len(loaded) == 1
    clean = loaded["fffd-0001"]
    assert "\ufffd" not in str(clean.get("headline") or "")
    assert "\ufffd" not in str(clean["members"][0].get("headline") or "")
    assert "\ufffd" not in str(clean["members"][0].get("excerpt") or "")
    # Brief contained U+FFFD -> dropped so it regenerates.
    assert clean.get("lastBrief") is None
    assert clean.get("lastBriefFingerprint") is None
    assert not stored_brief_usable(clean)


def test_legit_same_event_persisted_cluster_stays_merged(tmp_path):
    first = _wten_award()
    second = dict(first, id="wten-award-002", sourceId="wten2",
                  headline="Ballston Spa linebacker up for Heart of a Giant honor",
                  excerpt=("A Ballston Spa linebacker is among the nominees for "
                           "the Heart of a Giant Award."),
                  url="https://wten.com/2026/09/23/ballston-spa-award-2/",
                  publishedAt="2026-09-23T09:40:00Z")
    members = [first, second]
    rec = _persisted_record("legit-0001", members, headline=first["headline"],
                            brief=_good_brief())
    state_path = tmp_path / "clusters.json"
    _write_state(state_path, {"legit-0001": rec})

    loaded = load_state(state_path)
    assert len(loaded) == 1
    kept = loaded["legit-0001"]
    assert sorted(kept.get("memberIds", [])) == sorted(["wten-award-001", "wten-award-002"])
    # Unchanged membership -> stored brief still usable.
    assert stored_brief_usable(kept)
