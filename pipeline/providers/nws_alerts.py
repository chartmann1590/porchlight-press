"""NWS alerts provider (US public-safety items, public domain)."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping

from . import ProviderError, RawItem
from .http import get_bytes


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except ValueError:
        return None


class NwsAlertsProvider:
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
                self.client,
                url,
                max_body_bytes=self.max_body_bytes,
                extra_headers={"Accept": "application/geo+json"},
            )
            payload = resp.json()
        except Exception as exc:  # noqa: BLE001
            raise ProviderError(sid, "http", str(exc)[:300]) from exc

        items: list[RawItem] = []
        for feat in payload.get("features", []) if isinstance(payload, dict) else []:
            props = feat.get("properties", {}) or {}
            headline = (props.get("headline") or props.get("event") or "").strip()
            if not headline:
                continue
            body_parts = [p for p in (props.get("description"), props.get("instruction")) if p]
            items.append(
                RawItem(
                    source_id=sid,
                    publisher="National Weather Service",
                    title=headline,
                    url=str(props.get("@id") or url),
                    summary_html="\n\n".join(body_parts) if body_parts else None,
                    published_at=_parse_time(props.get("sent")),
                    language="en",
                    extra={
                        "event": props.get("event"),
                        "severity": props.get("severity"),
                        "expires": props.get("expires"),
                    },
                )
            )
        return items
