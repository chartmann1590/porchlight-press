"""Rights-matrix tests: METADATA_ONLY never yields an excerpt, etc."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from pipeline.normalize import normalize_item
from pipeline.providers import RawItem
from pipeline.rights import is_ingestible

NOW = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)


def _raw(**kw):
    base = {"source_id": "s", "publisher": "Pub", "title": "Some headline here",
            "url": "https://example.com/story?utm_source=rss",
            "summary_html": "<p>" + ("Word " * 100) + "</p>",
            "published_at": NOW - timedelta(hours=1), "language": "en"}
    base.update(kw)
    return RawItem(**base)


def _src(mode: str):
    return {"id": "s", "name": "Pub", "rightsMode": mode, "language": "en"}


def test_rss_excerpt_capped_at_300():
    item = normalize_item(_raw(), _src("RSS_EXCERPT_ALLOWED"), now=NOW)
    assert item is not None and item.excerpt is not None
    assert len(item.excerpt) <= 300


def test_metadata_only_never_yields_excerpt():
    item = normalize_item(_raw(), _src("METADATA_ONLY"), now=NOW)
    assert item is not None
    assert item.excerpt is None  # even though the feed offered a summary


def test_link_only_never_yields_excerpt():
    item = normalize_item(_raw(), _src("LINK_ONLY"), now=NOW)
    assert item is not None and item.excerpt is None


def test_public_domain_keeps_longer_text():
    item = normalize_item(_raw(), _src("PUBLIC_DOMAIN"), now=NOW)
    assert item is not None and item.excerpt is not None
    assert len(item.excerpt) > 300


def test_blocked_not_ingestible():
    assert not is_ingestible("BLOCKED")
    assert is_ingestible("METADATA_ONLY")


def test_missing_url_or_headline_dropped():
    assert normalize_item(_raw(url=""), _src("RSS_EXCERPT_ALLOWED"), now=NOW) is None
    assert normalize_item(_raw(title="  "), _src("RSS_EXCERPT_ALLOWED"), now=NOW) is None


def test_future_timestamp_rejected():
    raw = _raw(published_at=NOW + timedelta(hours=2))
    assert normalize_item(raw, _src("RSS_EXCERPT_ALLOWED"), now=NOW) is None


def test_slightly_future_timestamp_tolerated():
    raw = _raw(published_at=NOW + timedelta(minutes=30))
    assert normalize_item(raw, _src("RSS_EXCERPT_ALLOWED"), now=NOW) is not None


def test_item_id_stable_for_same_url():
    a = normalize_item(_raw(), _src("RSS_EXCERPT_ALLOWED"), now=NOW)
    b = normalize_item(_raw(), _src("METADATA_ONLY"), now=NOW)
    assert a is not None and b is not None
    assert a.id == b.id  # same canonical URL -> same id regardless of mode
