"""Dedup tests: exact URL + near-dup headline tables, 48 h window."""
from pipeline.dedupe import deduplicate, headline_similarity, normalize_headline


def _item(iid, headline, url, published_at="2026-09-23T10:00:00Z"):
    return {"id": iid, "headline": headline, "url": url,
            "publishedAt": published_at, "sourceId": "s", "publisher": "P"}


def test_normalize_strips_punctuation_and_stopwords():
    assert normalize_headline("Fire Breaks Out on Central Avenue!") == "fire breaks central avenue"
    assert normalize_headline("The Council Approves It") == "council approves it"


def test_exact_url_match_is_duplicate():
    a = _item("a", "Council approves downtown project", "https://example.com/a")
    b = _item("b", "Something completely different here", "https://example.com/a")
    unique, dups = deduplicate([a, b])
    assert len(unique) == 1 and sum(len(v) for v in dups.values()) == 1


def test_near_dup_above_threshold_groups():
    a = _item("a", "City council approves downtown revitalization project",
              "https://example.com/a")
    b = _item("b", "City council approves downtown revitalization plan",
              "https://example.com/b")
    assert headline_similarity(a["headline"], b["headline"]) >= 85
    unique, _ = deduplicate([a, b])
    assert len(unique) == 1


def test_different_headlines_stay_separate():
    a = _item("a", "Fire breaks out on Central Avenue in Albany",
              "https://example.com/a")
    b = _item("b", "City council approves downtown revitalization project",
              "https://example.com/b")
    unique, _ = deduplicate([a, b])
    assert len(unique) == 2


def test_window_outside_48h_not_duplicate():
    a = _item("a", "City council approves downtown revitalization project",
              "https://example.com/a", "2026-09-20T10:00:00Z")
    b = _item("b", "City council approves downtown revitalization project",
              "https://example.com/b", "2026-09-23T10:00:00Z")  # 72 h later
    unique, _ = deduplicate([a, b], threshold=85, window_hours=48)
    assert len(unique) == 2


def test_deterministic_earliest_wins():
    a = _item("a", "Same headline here today", "https://example.com/a",
              "2026-09-23T11:00:00Z")
    b = _item("b", "Same headline here today", "https://example.com/b",
              "2026-09-23T09:00:00Z")
    unique, dups = deduplicate([a, b])  # input order shuffled vs time
    assert unique[0]["id"] == "b"
