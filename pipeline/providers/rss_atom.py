"""RSS/Atom provider. feedparser for lenient parsing; defusedxml screens for
entity-expansion attacks (billion laughs) before parsing.

Encoding: feed bytes always go to feedparser untouched when they decode
cleanly under their declared (XML declaration, then HTTP Content-Type)
charset. Only when the declared charset fails -- the classic mislabeled feed
(cp1252 bytes served as UTF-8) -- do we fall back to windows-1252, which
rescues 0x80-0x9F punctuation instead of yielding U+FFFD, and re-emit clean
UTF-8 with a corrected declaration. Never decode with the wrong codec and
errors='replace': that is what publishes mojibake.
"""
from __future__ import annotations

import calendar
import re
from datetime import datetime, timezone
from typing import Any, Mapping

import feedparser
from defusedxml import EntitiesForbidden

from . import ProviderError, RawItem
from .http import get_bytes

_XML_ENCODING_RE = re.compile(rb"<\?xml[^>]*encoding\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)
_XML_ENCODING_VALUE_RE = re.compile(
    rb"(?P<head><\?xml[^>]*encoding\s*=\s*[\"'])(?P<enc>[^\"']+)(?P<quote>[\"'])",
    re.IGNORECASE,
)
_CONTENT_CHARSET_RE = re.compile(r"charset\s*=\s*[\"']?([^\"';\s]+)", re.IGNORECASE)
_UTF8_BOM = b"\xef\xbb\xbf"


def _declared_encoding(content: bytes) -> str | None:
    """Encoding from the XML declaration (ASCII-safe: searched on bytes)."""
    if content.startswith(_UTF8_BOM):
        return "utf-8-sig"
    match = _XML_ENCODING_RE.search(content[:4096])
    if not match:
        return None
    try:
        return match.group(1).decode("ascii").strip().lower() or None
    except (UnicodeDecodeError, ValueError):
        return None


def _http_charset(content_type: str | None) -> str | None:
    if not content_type:
        return None
    match = _CONTENT_CHARSET_RE.search(content_type)
    return match.group(1).strip().lower() or None if match else None


def decode_feed_bytes(content: bytes, content_type: str | None = None) -> bytes:
    """Return feedparser-ready bytes honoring declaration then HTTP charset.

    Fast path: bytes that decode strictly under the XML-declared encoding
    (or under any charset when there is no declaration to contradict) are
    returned unchanged. Otherwise -- the declared charset is wrong (e.g.
    declaration says UTF-8 but only the HTTP charset windows-1252 decodes
    the bytes) or nothing known decodes them -- transcode with the working
    encoding (windows-1252 as the last resort: a superset of ISO-8859-1
    that maps stray 0x80-0x9F bytes to their intended punctuation) and
    re-emit clean UTF-8 with a corrected declaration, so feedparser never
    decodes with the wrong codec (its internal errors='replace' is what
    surfaces U+FFFD downstream).
    """
    if not content:
        return content
    declared = _declared_encoding(content)
    candidates = [e for e in (declared, _http_charset(content_type), "utf-8") if e]
    working: str | None = None
    seen: set[str] = set()
    for enc in candidates:
        key = enc.lower().replace("_", "-")
        if key in seen:
            continue
        seen.add(key)
        try:
            content.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
        working = enc
        break
    if working is not None and (
        not declared
        or working.lower().replace("_", "-") == declared.lower().replace("_", "-")
    ):
        return content  # declared encoding verified (or none to contradict)
    text = content.decode(working) if working else content.decode("windows-1252")
    raw = text.encode("utf-8")
    if declared and declared != "utf-8-sig":
        patched, n = _XML_ENCODING_VALUE_RE.subn(rb"\g<head>UTF-8\g<quote>", raw, count=1)
        return patched if n else raw
    return raw


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
            from . import provider_error_from_http

            raise provider_error_from_http(sid, exc) from exc

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
        # Honor the XML declaration / HTTP charset before parsing: mislabeled
        # feeds are transcoded to clean UTF-8 here so neither feedparser nor
        # any downstream consumer ever decodes with the wrong codec.
        content = decode_feed_bytes(resp.content, resp.headers.get("Content-Type"))
        _reject_entity_attacks(content, sid)

        parsed = feedparser.parse(content)
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
