"""Source-health tests (offline, httpx.MockTransport + stub providers)."""
from __future__ import annotations

from datetime import datetime, timezone

import httpx

from pipeline.health import check_registry, check_source
from pipeline.providers import RawItem

FIX_RSS = (
    b'<?xml version="1.0"?><rss version="2.0"><channel><title>T</title>'
    b'<link>https://example.com/</link>'
    b'<item><title>Fresh item headline</title><link>https://example.com/1</link>'
    b'<pubDate>Wed, 23 Sep 2026 12:00:00 GMT</pubDate></item>'
    b"</channel></rss>"
)


def _client_for(body: bytes, status: int = 200) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, content=body)

    return httpx.Client(transport=httpx.MockTransport(handler))


def _rss_source(**kw):
    base = {"id": "ex", "name": "Example", "type": "rss",
            "feedUrl": "https://example.com/feed", "language": "en",
            "rightsMode": "RSS_EXCERPT_ALLOWED"}
    base.update(kw)
    return base


def _deps(client):
    return {"client": client, "max_body_bytes": 2_097_152}


def test_healthy_feed_ok_with_item_count():
    health = check_source(_rss_source(), _deps(_client_for(FIX_RSS)))
    assert health.ok and health.item_count == 1 and health.issues == []


def test_dead_feed_not_ok_but_explained():
    health = check_source(_rss_source(), _deps(_client_for(b"nope", status=500)))
    assert not health.ok
    assert any(i.startswith("http") for i in health.issues)


def test_permanent_redirect_reported_without_failing(monkeypatch):
    import pipeline.health as health_mod

    class StubProvider:
        def fetch(self, source, http_state=None):
            if http_state is not None:
                http_state[source["id"]] = {
                    "finalUrl": "https://example.com/new-feed",
                    "permanentRedirect": True,
                }
            return [RawItem(source_id=source["id"], publisher="P",
                             title="Moved feed item",
                             url="https://example.com/new-feed/1",
                             published_at=datetime.now(timezone.utc))]

    monkeypatch.setattr(health_mod, "get_provider", lambda *a, **k: StubProvider())
    health = check_source(_rss_source(), _deps(_client_for(b"")))
    assert health.ok  # redirect is a note, not a failure
    assert any("permanent-redirect" in i for i in health.issues)


def test_stale_feed_flagged():
    old = FIX_RSS.replace(b"23 Sep 2026", b"01 Jan 2020")
    health = check_source(_rss_source(), _deps(_client_for(old)), stale_days=7)
    assert health.ok
    assert any(i.startswith("stale") for i in health.issues)


def test_registry_helpers_reexported():
    assert check_registry([{"id": "a", "rightsMode": "RSS_EXCERPT_ALLOWED"}]) == []
