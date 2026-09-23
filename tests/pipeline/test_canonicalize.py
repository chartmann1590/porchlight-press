"""Canonicalization table tests + HTML-to-text."""
from pipeline.normalize import canonicalize_url, html_to_text


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
