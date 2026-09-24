"""Persisted-state revalidation: heal pre-#12 place-only merges + U+FFFD on load."""
import json

from pipeline.state import (
    cluster_content_fingerprint,
    load_state,
    stored_brief_usable,
)

_BALLSTON_LOC = [{"country": "US", "admin1": "US-NY", "admin2": "Saratoga County",
                  "city": "Ballston Spa", "metro": "us-ny-capital-region"}]


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


def _persisted_record(event_id, members, headline=None, brief=None):
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
        "locations": _BALLSTON_LOC,
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


def test_persisted_ballston_pair_splits_and_brief_not_reused(tmp_path):
    members = [_wten_award(), _wamc_cancer()]
    rec = _persisted_record("0d0cf2f78345feea", members, brief=_good_brief())
    state_path = tmp_path / "clusters.json"
    _write_state(state_path, {"0d0cf2f78345feea": rec})

    loaded = load_state(state_path)
    # Split into two single-member clusters.
    assert len(loaded) == 2
    all_member_ids = sorted([mid for r in loaded.values() for mid in r.get("memberIds", [])])
    assert all_member_ids == sorted(["wten-award-001", "wamc-cancer-002"])
    # Original eventId stays with anchor (earliest member = award).
    assert "0d0cf2f78345feea" in loaded
    assert loaded["0d0cf2f78345feea"]["memberIds"] == ["wten-award-001"]
    # Split survivor must not reuse the old 2-member brief.
    assert not stored_brief_usable(loaded["0d0cf2f78345feea"])
    assert loaded["0d0cf2f78345feea"].get("lastBrief") is None
    # Split-off has no brief either.
    other_ids = [k for k in loaded if k != "0d0cf2f78345feea"]
    assert len(other_ids) == 1
    assert loaded[other_ids[0]]["memberIds"] == ["wamc-cancer-002"]
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
