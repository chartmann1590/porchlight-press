"""Provider tests (offline, httpx.MockTransport)."""
from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from pipeline.providers import ProviderError
from pipeline.providers.gdelt import GdeltProvider, build_query
from pipeline.providers.json_api import JsonApiProvider
from pipeline.providers.nws_alerts import NwsAlertsProvider
from pipeline.providers.rss_atom import RssAtomProvider

FIX = Path(__file__).resolve().parent.parent / "fixtures"


def _client_for(mapping: dict[str, bytes], status: int = 200) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        for prefix, body in mapping.items():
            if str(request.url).startswith(prefix):
                return httpx.Response(status, content=body)
        return httpx.Response(404, content=b"no mock")

    return httpx.Client(transport=httpx.MockTransport(handler))


def _rss_source(**kw):
    base = {"id": "ex", "name": "Example", "type": "rss",
            "feedUrl": "https://example.com/feed", "language": "en"}
    base.update(kw)
    return base


def test_rss_parses_two_items_and_keeps_raw_summary():
    client = _client_for({"https://example.com": (FIX / "rss_valid.xml").read_bytes()})
    items = RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert len(items) == 2
    assert items[0].title == "Council approves downtown project"
    assert "<b>approved</b>" in (items[0].summary_html or "")
    assert items[0].published_at is not None


def test_atom_parses_entries_and_content():
    client = _client_for({"https://example.com": (FIX / "atom_valid.xml").read_bytes()})
    items = RssAtomProvider(client, 2_097_152).fetch(_rss_source(feedUrl="https://example.com/atom"))
    assert len(items) == 2
    assert items[1].title == "Budget vote scheduled Tuesday"


def test_malformed_xml_raises_provider_error():
    client = _client_for({"https://example.com": (FIX / "rss_malformed.xml").read_bytes()})
    with pytest.raises(ProviderError) as ei:
        RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert ei.value.kind == "invalid-xml"


def test_billion_laughs_rejected():
    client = _client_for({"https://example.com": (FIX / "rss_billion_laughs.xml").read_bytes()})
    with pytest.raises(ProviderError) as ei:
        RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert ei.value.kind == "invalid-xml"


def test_http_error_wrapped_as_provider_error():
    client = _client_for({}, status=500)
    with pytest.raises(ProviderError) as ei:
        RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert ei.value.kind == "http"


def test_gdelt_query_built_from_coverage():
    q = build_query({"id": "g", "name": "G",
                     "coverage": {"cities": ["Albany", "Schenectady"], "admin1": "US-NY",
                                  "admin2": ["Albany County"]}})
    assert '"Albany"' in q and '"New York"' in q
    # Raw ISO codes are never sent: "NY" must not appear as its own term.
    assert '"NY"' not in q.split(" OR ")


def test_gdelt_non_us_admin1_uses_registry_name_not_raw_code():
    q = build_query({"id": "g", "name": "G",
                     "coverage": {"country": "CA", "cities": ["Toronto"],
                                  "admin1": "CA-ON", "admin1Name": "Ontario"}})
    assert '"Ontario"' in q
    assert '"ON"' not in q.split(" OR ")
    assert "CA-ON" not in q


def test_gdelt_non_us_admin1_without_name_is_dropped():
    q = build_query({"id": "g", "name": "G",
                     "coverage": {"country": "GB", "cities": ["London"],
                                  "admin1": "GB-ENG"}})
    assert '"London"' in q
    assert "GB-ENG" not in q
    assert '"ENG"' not in q.split(" OR ")


def test_gdelt_unknown_us_admin1_is_dropped():
    q = build_query({"id": "g", "name": "G",
                     "coverage": {"cities": ["Albany"], "admin1": "US-XX"}})
    assert '"Albany"' in q
    assert '"XX"' not in q.split(" OR ")
    assert "US-XX" not in q


def test_gdelt_rate_limit_is_nonfatal_and_specific(monkeypatch):
    import pipeline.providers.gdelt as gdelt_mod

    monkeypatch.setattr(gdelt_mod.time, "sleep", lambda s: None)
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(429, content=b"slow down")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    src = {"id": "g", "name": "GDELT", "type": "gdelt",
           "coverage": {"cities": ["Albany"], "admin1": "US-NY"}}
    with pytest.raises(ProviderError) as ei:
        GdeltProvider(client, 2_097_152).fetch(src)
    assert ei.value.kind == "rate-limited"
    assert len(calls) == 1  # 429s are never retried


def test_gdelt_parses_discovery_items(monkeypatch):
    import pipeline.providers.gdelt as gdelt_mod

    monkeypatch.setattr(gdelt_mod.time, "sleep", lambda s: None)
    body = (FIX / "gdelt_sample.json").read_bytes()
    client = _client_for({"https://api.gdeltproject.org": body})
    src = {"id": "g", "name": "GDELT", "type": "gdelt",
           "coverage": {"cities": ["Albany"], "admin1": "US-NY"}}
    items = GdeltProvider(client, 2_097_152).fetch(src)
    assert len(items) == 1
    assert items[0].summary_html is None  # discovery only
    assert items[0].published_at is not None


def test_rss_rate_limit_maps_to_rate_limited():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, content=b"slow down")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    with pytest.raises(ProviderError) as ei:
        RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert ei.value.kind == "rate-limited"


def test_nws_rate_limit_maps_to_rate_limited():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, content=b"slow down")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    src = {"id": "nws", "name": "NWS", "type": "nws-alerts",
           "apiUrl": "https://api.weather.gov/alerts/active?point=1,2"}
    with pytest.raises(ProviderError) as ei:
        NwsAlertsProvider(client, 2_097_152).fetch(src)
    assert ei.value.kind == "rate-limited"


def test_nws_parses_alert_with_public_safety_text():
    body = (FIX / "nws_sample.json").read_bytes()
    client = _client_for({"https://api.weather.gov": body})
    src = {"id": "nws", "name": "NWS", "type": "nws-alerts",
           "apiUrl": "https://api.weather.gov/alerts/active?point=1,2"}
    items = NwsAlertsProvider(client, 2_097_152).fetch(src)
    assert len(items) == 1
    assert "Schenectady County" in items[0].title
    assert "higher ground" in (items[0].summary_html or "")
    assert items[0].extra["severity"] == "Severe"


def test_usgs_parses_geojson():
    body = (FIX / "usgs_sample.json").read_bytes()
    client = _client_for({"https://earthquake.usgs.gov": body})
    src = {"id": "usgs", "name": "USGS", "type": "json-api",
           "apiUrl": "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson"}
    items = JsonApiProvider(client, 2_097_152).fetch(src)
    assert len(items) == 1
    assert items[0].title.startswith("M 5.1")
    assert items[0].published_at is not None


# --- fix/cluster-overmerge-encoding: feed charset handling, no U+FFFD ---

def _rss_bytes(title: bytes, *, declaration: bytes = b'<?xml version="1.0" encoding="UTF-8"?>') -> bytes:
    return (declaration
            + b'<rss version="2.0"><channel><title>t</title><link>https://example.com/</link>'
            b'<item><title>' + title + b'</title><link>https://example.com/1</link>'
            b'<description>summary here</description>'
            b'<pubDate>Tue, 23 Sep 2026 09:05:00 GMT</pubDate>'
            b'</item></channel></rss>')


def _client_for_body(body: bytes, content_type: str | None = None) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        headers = {"Content-Type": content_type} if content_type else {}
        return httpx.Response(200, content=body, headers=headers)

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_rss_windows1252_feed_decodes_em_dash():
    # Declared windows-1252 with a raw 0x97 em dash: must arrive as U+2014,
    # never U+FFFD.
    body = _rss_bytes(b'A husband got cancer \x97 his friends stepped up',
                      declaration=b'<?xml version="1.0" encoding="windows-1252"?>')
    client = _client_for_body(body, "application/rss+xml")
    items = RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert len(items) == 1
    assert items[0].title == "A husband got cancer \u2014 his friends stepped up"
    assert "\ufffd" not in items[0].title


def test_rss_mislabeled_utf8_feed_rescued_not_replacement_char():
    # Production WAMC case: feed declares UTF-8 but carries a cp1252 byte
    # (the em dash). Must be rescued to U+2014, never published as U+FFFD.
    body = _rss_bytes(b'A husband got cancer \x97 his friends stepped up')
    client = _client_for_body(body, "application/rss+xml;charset=UTF-8")
    items = RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert len(items) == 1
    assert items[0].title == "A husband got cancer \u2014 his friends stepped up"
    assert "\ufffd" not in items[0].title


def test_rss_http_charset_honored_without_declaration():
    # No XML declaration: the HTTP Content-Type charset decides.
    body = _rss_bytes(b'A husband got cancer \x97 his friends stepped up',
                      declaration=b'')
    client = _client_for_body(body, "text/xml; charset=windows-1252")
    items = RssAtomProvider(client, 2_097_152).fetch(_rss_source())
    assert len(items) == 1
    assert items[0].title == "A husband got cancer \u2014 his friends stepped up"
    assert "\ufffd" not in items[0].title


def test_rss_clean_utf8_bytes_pass_through_unchanged():
    # Well-formed feeds must reach feedparser byte-identical (no behavior
    # change for the common case).
    from pipeline.providers.rss_atom import decode_feed_bytes

    body = _rss_bytes("A husband got cancer \u2014 his friends".encode("utf-8"))
    assert decode_feed_bytes(body, "application/rss+xml;charset=UTF-8") == body


def test_decode_rewrites_declaration_on_rescue():
    # The rescue path re-emits strict UTF-8 with a corrected declaration.
    from pipeline.providers.rss_atom import decode_feed_bytes

    body = _rss_bytes(b'A husband got cancer \x97 his friends stepped up')
    fixed = decode_feed_bytes(body, "application/rss+xml;charset=UTF-8")
    assert fixed != body
    assert b'encoding="UTF-8"' in fixed
    text = fixed.decode("utf-8")  # strict: must not raise
    assert "A husband got cancer \u2014 his friends stepped up" in text
    assert "\ufffd" not in text
