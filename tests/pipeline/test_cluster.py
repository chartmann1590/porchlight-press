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


# --- fix/cluster-overmerge-encoding: shared place name alone never merges ---

# Production story 0d0cf2f78345feea: WTEN's award nomination and WAMC's
# cancer-support story were merged into one cluster. They share nothing but
# the place name (Ballston Spa) -- same city, adjacent timestamps.
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


def test_place_only_pair_does_not_cluster():
    clusters = cluster_items([_wten_award(), _wamc_cancer()])
    assert len(clusters) == 2
    assert {len(c["members"]) for c in clusters} == {1}


def test_place_only_pair_does_not_merge():
    ca = cluster_items([_wten_award()])[0]
    cb = cluster_items([_wamc_cancer()])[0]
    assert len(maybe_merge_clusters([ca, cb])) == 2


def test_same_event_pair_still_clusters():
    # Two wordings of the same award story: shared non-place entities and
    # real textual overlap must keep joining.
    second = _wten_award()
    second = dict(second, id="wten-award-002", sourceId="wten2", publisher="WTEN",
                  headline="Ballston Spa linebacker up for Heart of a Giant honor",
                  excerpt=("A Ballston Spa linebacker is among the nominees for "
                           "the Heart of a Giant Award."),
                  url="https://wten.com/2026/09/23/ballston-spa-award-2/",
                  publishedAt="2026-09-23T09:40:00Z")
    clusters = cluster_items([_wten_award(), second])
    assert len(clusters) == 1
    assert len(clusters[0]["members"]) == 2


# --- fix/place-entity-gate: unlisted municipality names are never evidence ---
#
# Live 0d0cf2f78345feea follow-up: the pair above splits only because the
# fixture locations resolve TO Ballston Spa. The live items locate to
# Albany/Capital Region (Ballston Spa appears only in prose, missing from
# the trimmed gazetteer), so in the full-run corpus (cos=0.090) the shared
# 'ballston spa' entity passed the gate and re-merged them. The committed
# NY municipality list (Census Gazetteer, PD) now excludes such names in
# every corpus size.

# Live-like locations: metro/city resolved, town name present only in prose.
_UNLISTED_TOWN_LOC = [{"country": "US", "admin1": "US-NY",
                       "admin2": "Albany County", "city": "Albany",
                       "metro": "us-ny-capital-region"}]


def _bakery_prize():
    return {
        "id": "wten-bakery-001", "sourceId": "wten", "publisher": "WTEN",
        "headline": "Ballston Spa bakery wins regional pastry prize",
        "excerpt": ("A family bakery in Ballston Spa took first place at the "
                    "regional pastry showcase on Saturday."),
        "url": "https://wten.com/2026/09/23/ballston-spa-bakery/",
        "publishedAt": "2026-09-23T22:00:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }


def _creek_cleanup():
    return {
        "id": "wamc-creek-002", "sourceId": "wamc", "publisher": "WAMC",
        "headline": "Ballston Spa teen organizes creek cleanup",
        "excerpt": ("A Ballston Spa teenager recruited volunteers to clear "
                    "debris from the creek over the weekend."),
        "url": "https://wamc.org/2026/09/24/ballston-spa-creek-cleanup/",
        "publishedAt": "2026-09-24T15:30:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }


def test_unlisted_town_name_does_not_cluster():
    from pipeline.cluster import extract_entities, place_names_for_item

    a, b = _bakery_prize(), _creek_cleanup()
    ea = extract_entities(f"{a['headline']} {a['excerpt']}")
    eb = extract_entities(f"{b['headline']} {b['excerpt']}")
    # Fixture contract: the ONLY shared entity is the unlisted town, and no
    # resolved location names it (otherwise this would duplicate the older
    # place-only test above instead of the live gap).
    assert ea & eb == {"ballston spa"}
    assert "ballston spa" not in (place_names_for_item(a) | place_names_for_item(b))
    clusters = cluster_items([a, b])
    assert len(clusters) == 2
    assert {len(c["members"]) for c in clusters} == {1}


def test_unlisted_town_name_does_not_merge():
    ca = cluster_items([_bakery_prize()])[0]
    cb = cluster_items([_creek_cleanup()])[0]
    assert len(maybe_merge_clusters([ca, cb])) == 2


def test_genuine_two_source_pair_still_clusters():
    # Santa-style same-event pair: shared person names + real textual
    # overlap must keep joining (and merging) despite place-heavy prose.
    first = {
        "id": "wten-santa-001", "sourceId": "wten", "publisher": "WTEN",
        "headline": "Clarence Russell sought in Santa impersonator case",
        "excerpt": ("Police say Clarence Russell performed as Santa Claus at "
                    "holiday events before the alleged assault."),
        "url": "https://wten.com/2026/09/24/santa-impersonator-wanted/",
        "publishedAt": "2026-09-24T02:00:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }
    second = {
        "id": "wten-santa-002", "sourceId": "wten2", "publisher": "WTEN",
        "headline": "Ex-Santa performer Clarence Russell surrenders",
        "excerpt": ("Clarence Russell, the former Santa Claus performer, turned "
                    "himself in on the assault charges, troopers said."),
        "url": "https://wten.com/2026/09/25/santa-impersonator-surrenders/",
        "publishedAt": "2026-09-25T00:40:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }
    clusters = cluster_items([first, second])
    assert len(clusters) == 1
    assert len(clusters[0]["members"]) == 2


def test_municipality_gate_covers_live_towns():
    from pipeline.cluster import (
        has_genuine_overlap,
        load_municipality_names,
        place_aware_entity_similarity,
    )

    muni = load_municipality_names()
    assert {"ballston spa", "poestenkill", "kingston", "poughkeepsie"} <= set(muni)
    # Live pair entity sets: the only shared span is the town.
    ea = {"ballston spa", "football", "giant award", "heart", "hospital",
          "new york giants", "special surgery"}
    eb = {"ballston spa", "nick henry"}
    assert place_aware_entity_similarity(ea, eb, ignore=frozenset()) == 0.0
    # Gate at the measured cosines: full-run 0.090 with no entity evidence
    # stays split (why MIN_ENTITY_TEXT_SIM was NOT raised -- a
    # corpus-dependent threshold is fragile); genuine-level overlap merges.
    assert not has_genuine_overlap(0.090, 0.0)
    assert has_genuine_overlap(0.31, 1.0)


def test_st_johnsville_shared_name_does_not_cluster():
    from pipeline.cluster import extract_entities, load_municipality_names

    muni = load_municipality_names()
    assert "st. johnsville" in muni
    assert "johnsville" in muni  # entity-form alias for "St." prose
    a = {
        "id": "wten-johnsville-001", "sourceId": "wten", "publisher": "WTEN",
        "headline": "St. Johnsville bakery wins regional pastry prize",
        "excerpt": ("A family bakery in St. Johnsville took first place at the "
                    "regional pastry showcase on Saturday."),
        "url": "https://wten.com/2026/09/23/st-johnsville-bakery/",
        "publishedAt": "2026-09-23T22:00:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }
    b = {
        "id": "wamc-johnsville-002", "sourceId": "wamc", "publisher": "WAMC",
        "headline": "St. Johnsville teen organizes creek cleanup",
        "excerpt": ("A St. Johnsville teenager recruited volunteers to clear "
                    "debris from the creek over the weekend."),
        "url": "https://wamc.org/2026/09/24/st-johnsville-creek-cleanup/",
        "publishedAt": "2026-09-24T15:30:00Z", "rightsMode": "RSS_EXCERPT_ALLOWED",
        "locations": _UNLISTED_TOWN_LOC,
    }
    ea = extract_entities(f"{a['headline']} {a['excerpt']}")
    eb = extract_entities(f"{b['headline']} {b['excerpt']}")
    assert ea & eb == {"johnsville"}  # "St" drops on the period split
    clusters = cluster_items([a, b])
    assert len(clusters) == 2


def test_municipality_loader_null_names_returns_empty(tmp_path):
    from pipeline.cluster import load_municipality_names

    p_null = tmp_path / "muni_null.json"
    p_null.write_text('{"names": null}', encoding="utf-8")
    assert load_municipality_names(str(p_null)) == frozenset()
    p_str = tmp_path / "muni_str.json"
    p_str.write_text('{"names": "not-a-list"}', encoding="utf-8")
    assert load_municipality_names(str(p_str)) == frozenset()
    p_mixed = tmp_path / "muni_mixed.json"
    p_mixed.write_text('{"names": ["Albany", 123, null, "  "]}', encoding="utf-8")
    assert load_municipality_names(str(p_mixed)) == frozenset({"albany"})
