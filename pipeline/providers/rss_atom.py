"""RSS/Atom provider. feedparser for lenient parsing; defusedxml screens for
entity-expansion attacks (billion laughs) before parsing."""
from __future__ import annotations

import calendar
from datetime import datetime, timezone
from typing import Any, Mapping

import feedparser
from defusedxml import EntitiesForbidden

from . import ProviderError, RawItem
from .http import get_bytes


def _reject_entity_attacks(content: bytes, sid: str) -> None:
    """Reject inline DTD entity/element declarations outright, then let
    defusedxml confirm no forbidden entities survive. Plain malformed or
    HTML-sloppy feeds (e.g. bare &nbsp;) fall through to feedparser, which
    handles them; only expansion attacks are fatal here."""
    head = content[:200_000].upper()
    if b"<!ENTITY" in head or b"<!ELEMENT" in head:
        raise ProviderError(sid, "invalid-xml", "rejected: inline ENTITY/ELEMENT declarations")
    try:
        from defusedxml.ElementTree import fromstring

        fromstring(content)
    except EntitiesForbidden as exc:
        raise ProviderError(sid, "invalid-xml", f"rejected: {exc}") from exc
    except Exception:  # noqa: BLE001 - benign sloppiness; feedparser decides
        pass


def _entry_time(entry: Any) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        parsed = entry.get(key)
        if parsed:
            try:
                return datetime.fromtimestamp(calendar.timegm(parsed), tz=timezone.utc)
            except (OverflowError, OSError, ValueError):
                return None
    return None


class RssAtomProvider:
    def __init__(self, client, max_body_bytes: int):
        self.client = client
        self.max_body_bytes = max_body_bytes

    def fetch(
        self,
        source: Mapping[str, Any],
        http_state: dict[str, Any] | None = None,
    ) -> list[RawItem]:
        sid = str(source["id"])
        url = str(source.get("feedUrl") or source.get("apiUrl") or "")
        if not url:
            raise ProviderError(sid, "config", "missing feedUrl/apiUrl")
        headers: dict[str, str] = {}
        if http_state:
            slot = http_state.get(sid, {})
            if slot.get("etag"):
                headers["If-None-Match"] = slot["etag"]
            if slot.get("lastModified"):
                headers["If-Modified-Since"] = slot["lastModified"]
        try:
            resp = get_bytes(
                self.client, url, max_body_bytes=self.max_body_bytes, extra_headers=headers
            )
        except Exception as exc:  # noqa: BLE001 - wrapped as non-fatal
            raise ProviderError(sid, "http", str(exc)[:300]) from exc

        if http_state is not None:
            http_state[sid] = {
                "etag": resp.headers.get("ETag", ""),
                "lastModified": resp.headers.get("Last-Modified", ""),
                "finalUrl": str(resp.url),
                "permanentRedirect": any(
                    r.status_code in (301, 308) for r in resp.history
                ),
            }
        if resp.status_code == 304:
            return []
        _reject_entity_attacks(resp.content, sid)

        parsed = feedparser.parse(resp.content)
        if parsed.bozo and not parsed.entries:
            detail = str(getattr(parsed, "bozo_exception", "parse failed"))[:300]
            raise ProviderError(sid, "invalid-xml", detail)

        items: list[RawItem] = []
        for entry in parsed.entries:
            link = (entry.get("link") or "").strip()
            title = (entry.get("title") or "").strip()
            if not link or not title:
                continue
            summary = None
            if entry.get("summary"):
                summary = str(entry["summary"])
            elif entry.get("content"):
                try:
                    summary = str(entry["content"][0].get("value", ""))
                except (IndexError, AttributeError):
                    summary = None
            items.append(
                RawItem(
                    source_id=sid,
                    publisher=str(source["name"]),
                    title=title,
                    url=link,
                    summary_html=summary,
                    published_at=_entry_time(entry),
                    language=str(source.get("language") or "") or None,
                )
            )
        return items
