"""Canonicalization table tests + HTML-to-text."""
from datetime import datetime, timezone

from pipeline.normalize import canonicalize_url, html_to_text, normalize_item
from pipeline.providers import RawItem


def test_host_lowercased_and_fragment_dropped():
    assert canonicalize_url("https://EXAMPLE.com/Story#comments") == "https://example.com/Story"


def test_tracking_params_stripped_keeps_real_params():
    got = canonicalize_url("https://example.com/a?utm_source=rss&fbclid=x&id=42")
    assert got == "https://example.com/a?id=42"


def test_gclid_and_utm_prefix_stripped():
    got = canonicalize_url("https://example.com/a?gclid=abc&utm_foo=1")
    assert got == "https://example.com/a"


def test_trivial_amp_resolved():
    assert canonicalize_url("https://example.com/budget/amp") == "https://example.com/budget"
    assert canonicalize_url("https://example.com/x?output=amp&id=1") == "https://example.com/x?id=1"


def test_query_sorted_for_stability():
    assert (canonicalize_url("https://example.com/a?b=2&a=1")
            == "https://example.com/a?a=1&b=2")


def test_html_to_text_strips_tags_scripts_and_entities():
    html = "<p>Council <b>approved</b> it 5-2.</p><script>alert(1)</script> A&nbsp;B"
    assert html_to_text(html) == "Council approved it 5-2. A B"


def test_html_to_text_strips_replacement_chars():
    # A source that mangled its own bytes ships literal U+FFFD: last-resort
    # cleaning drops it instead of publishing mojibake.
    assert html_to_text("<p>got cancer \ufffd his friends</p>") == "got cancer his friends"
    assert html_to_text("don\ufffdt") == "dont"


def test_normalize_item_strips_replacement_chars():
    now = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)
    raw = RawItem(source_id="s", publisher="Pub",
                  title="got cancer \ufffd his friends",
                  url="https://example.com/story",
                  summary_html="<p>got cancer \ufffd his friends</p>",
                  published_at=now, language="en")
    item = normalize_item(raw, {"id": "s", "name": "Pub",
                                "rightsMode": "RSS_EXCERPT_ALLOWED", "language": "en"},
                          now=now)
    assert item is not None
    assert "\ufffd" not in item.headline
    assert item.headline == "got cancer his friends"
    assert item.excerpt is not None and "\ufffd" not in item.excerpt
