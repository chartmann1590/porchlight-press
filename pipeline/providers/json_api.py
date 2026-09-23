"""Generic JSON-API provider. Currently serves the USGS earthquake GeoJSON
(public domain) behind the `json-api` registry type; dispatch by domain keeps
future JSON feeds (municipal open-data, FEMA, etc.) pluggable without a new
provider class per feed."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping
from urllib.parse import urlparse

from . import ProviderError, RawItem
from .http import get_bytes


def _epoch_ms(value: Any) -> datetime | None:
    try:
        return datetime.fromtimestamp(float(value) / 1000, tz=timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def _usgs_items(source: Mapping[str, Any], payload: Any) -> list[RawItem]:
    sid = str(source["id"])
    items: list[RawItem] = []
    features = payload.get("features", []) if isinstance(payload, dict) else []
    for feat in features:
        props = feat.get("properties", {}) or {}
        title = (props.get("title") or "").strip()
        link = (props.get("url") or "").strip()
        if not title or not link:
            continue
        items.append(
            RawItem(
                source_id=sid,
                publisher="USGS Earthquake Hazards Program",
                title=title,
                url=link,
                summary_html=None,
                published_at=_epoch_ms(props.get("time")),
                language="en",
                extra={"mag": props.get("mag"), "place": props.get("place")},
            )
        )
    return items


class JsonApiProvider:
    def __init__(self, client, max_body_bytes: int):
        self.client = client
        self.max_body_bytes = max_body_bytes

    def fetch(self, source: Mapping[str, Any]) -> list[RawItem]:
        sid = str(source["id"])
        url = str(source.get("apiUrl") or source.get("feedUrl") or "")
        if not url:
            raise ProviderError(sid, "config", "missing apiUrl")
        try:
            resp = get_bytes(
                self.client, url, max_body_bytes=self.max_body_bytes,
                extra_headers={"Accept": "application/json"},
            )
            payload = resp.json()
        except Exception as exc:  # noqa: BLE001
            raise ProviderError(sid, "http", str(exc)[:300]) from exc
        host = urlparse(url).netloc.lower()
        if "earthquake.usgs.gov" in host:
            return _usgs_items(source, payload)
        raise ProviderError(sid, "config", f"no JSON-API handler for host {host}")
