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
