"""State tests: new/updated/unchanged, aliases, pruning, brief-hash preserve."""
from datetime import datetime, timezone

from pipeline.state import update_state

NOW = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)


def _rec(eid, members, version=1, first="2026-09-23T09:00:00Z", last="2026-09-23T10:00:00Z"):
    return {"eventId": eid, "members": members,
            "memberIds": [m["id"] for m in members], "aliases": [],
            "version": version, "firstSeen": first, "lastSeen": last,
            "lastBriefHash": "abc", "lastGeneratedAt": "2026-09-23T11:00:00Z"}


def _m(mid, sid):
    return {"id": mid, "sourceId": sid, "publisher": sid,
            "headline": "H", "url": f"https://example.com/{mid}",
            "publishedAt": "2026-09-23T10:00:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED"}


def test_new_cluster_version_one():
    out = update_state({}, [_rec("e1", [_m("m1", "a")])], {}, now=NOW)
    assert out["e1"]["status"] == "new" and out["e1"]["version"] == 1


def test_unchanged_keeps_version_and_hash():
    prev = {"e1": _rec("e1", [_m("m1", "a")])}
    new = [_rec("e1", [_m("m1", "a")])]
    out = update_state(prev, new, {}, now=NOW)
    assert out["e1"]["status"] == "unchanged"
    assert out["e1"]["version"] == 1 and out["e1"]["lastBriefHash"] == "abc"


def test_updated_on_new_independent_source_once_per_run():
    prev = {"e1": _rec("e1", [_m("m1", "a")])}
    new = [_rec("e1", [_m("m1", "a"), _m("m2", "b"), _m("m3", "c")])]
    out = update_state(prev, new, {}, now=NOW)
    assert out["e1"]["status"] == "updated"
    assert out["e1"]["version"] == 2  # at most +1 per run even with 2 joiners


def test_prune_older_than_7_days():
    prev = {"old": _rec("old", [_m("m1", "a")], first="2026-09-10T09:00:00Z",
                        last="2026-09-10T10:00:00Z")}
    out = update_state(prev, [], {}, now=NOW, prune_days=7)
    assert "old" not in out


def test_merge_missing_firstseen_sorts_as_newest():
    # Two previous clusters merge via shared members: the one with a real
    # (older) firstSeen must survive; a missing firstSeen sorts as newest
    # via datetime.max and never wins "oldest".
    prev = {
        "aaa": _rec("aaa", [_m("m1", "a")], first="2026-09-20T09:00:00Z"),
        "zzz": _rec("zzz", [_m("m2", "b")], first=None),
    }
    new = [_rec("fresh", [_m("m1", "a"), _m("m2", "b")])]
    out = update_state(prev, new, {}, now=NOW)
    assert "aaa" in out and "zzz" not in out
    assert "zzz" in out["aaa"]["aliases"]


def test_merge_future_dates_do_not_break_oldest_wins():
    prev = {
        "old": _rec("old", [_m("m1", "a")], first="2020-01-01T00:00:00Z"),
        "fut": _rec("fut", [_m("m2", "b")], first="9999-12-31T00:00:00Z"),
    }
    new = [_rec("fresh", [_m("m1", "a"), _m("m2", "b")])]
    out = update_state(prev, new, {}, now=NOW)
    assert "old" in out and "fut" not in out


def test_merge_two_missing_firstseen_tiebreaks_by_id():
    prev = {
        "bbb": _rec("bbb", [_m("m1", "a")], first=None),
        "aaa": _rec("aaa", [_m("m2", "b")], first=None),
    }
    new = [_rec("fresh", [_m("m1", "a"), _m("m2", "b")])]
    out = update_state(prev, new, {}, now=NOW)
    assert "aaa" in out and "bbb" not in out


def test_alias_resolution():
    prev = {"old-id": _rec("old-id", [_m("m1", "a")])}
    prev["old-id"]["aliases"] = []
    new_rec = _rec("new-id", [_m("m1", "a"), _m("m2", "b")])
    # Simulate a merge where new-id is known to be an alias of old-id.
    prev["old-id"]["aliases"] = ["new-id"]
    out = update_state(prev, [new_rec], {}, now=NOW)
    assert "old-id" in out and "new-id" not in out or out.get("old-id", {}).get("status") == "updated"
