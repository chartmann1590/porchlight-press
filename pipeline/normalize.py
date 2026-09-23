"""Normalization: one internal item format, rights enforced at the door."""
from __future__ import annotations

import hashlib
import html as html_lib
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Mapping
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

from .rights import excerpt_limit

TRACKING_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid", "igshid",
}

_TAG_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.DOTALL | re.IGNORECASE)
_HTML_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def canonicalize_url(url: str) -> str:
    """Lowercase host, strip tracking params/fragments, resolve trivial AMP."""
    url = url.strip()
    parts = urlparse(url)
    scheme = (parts.scheme or "https").lower()
    host = (parts.hostname or "").lower()
    port = f":{parts.port}" if parts.port not in (None, 80, 443) else ""
    path = parts.path or "/"
    # trivial AMP mapping: /foo/amp -> /foo/
    if path != "/" and path.rstrip("/").endswith("/amp"):
        path = path.rstrip("/")[: -len("/amp")] or "/"
    kept: list[tuple[str, str]] = []
    for key, value in parse_qsl(parts.query, keep_blank_values=True):
        kl = key.lower()
        if kl in TRACKING_PARAMS or kl.startswith("utm_"):
            continue
        if kl == "output" and value.lower() == "amp":
            continue
        if kl == "amp":
            continue
        kept.append((key, value))
    kept.sort()
    return urlunparse((scheme, host + port, path, "", urlencode(kept), ""))


def html_to_text(value: str | None) -> str:
    if not value:
        return ""
    text = _TAG_RE.sub(" ", value)
    text = _HTML_TAG_RE.sub(" ", text)
    text = html_lib.unescape(text)
    return _WS_RE.sub(" ", text).strip()


def truncate(text: str, limit: int) -> str:
    if limit <= 0 or len(text) <= limit:
        return text
    return text[:limit].rstrip()


@dataclass
class NormalizedItem:
    id: str
    sourceId: str
    publisher: str
    headline: str
    excerpt: str | None
    url: str
    publishedAt: str  # ISO 8601 UTC
    language: str
    rightsMode: str
    imageCandidate: str | None
    rawLocations: list[str]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def item_id_for(canonical_url: str) -> str:
    return hashlib.sha256(canonical_url.encode("utf-8")).hexdigest()[:32]


def normalize_item(
    raw: Any,
    source: Mapping[str, Any],
    *,
    now: datetime | None = None,
) -> NormalizedItem | None:
    """Return a NormalizedItem, or None when the item must be dropped
    (missing URL/headline, future timestamp > 1 h, BLOCKED source)."""
    moment = now or datetime.now(timezone.utc)
    rights_mode = str(source.get("rightsMode", "METADATA_ONLY"))

    url_raw = (getattr(raw, "url", "") or "").strip()
    headline = _WS_RE.sub(" ", (getattr(raw, "title", "") or "")).strip()
    if not url_raw or not headline:
        return None
    try:
        canonical = canonicalize_url(url_raw)
    except ValueError:
        return None

    published = getattr(raw, "published_at", None)
    if published is not None:
        if published.tzinfo is None:
            published = published.replace(tzinfo=timezone.utc)
        if (published - moment).total_seconds() > 3600:
            return None  # future timestamp > 1 h: reject
        published_iso = published.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    else:
        published_iso = moment.isoformat().replace("+00:00", "Z")

    limit = excerpt_limit(rights_mode)
    excerpt: str | None = None
    if limit > 0:
        text = html_to_text(getattr(raw, "summary_html", None))
        if text:
            excerpt = truncate(text, limit) or None

    language = getattr(raw, "language", None) or str(source.get("language", "en"))
    return NormalizedItem(
        id=item_id_for(canonical),
        sourceId=str(source["id"]),
        publisher=getattr(raw, "publisher", "") or str(source.get("name", "")),
        headline=headline[:200],
        excerpt=excerpt,
        url=canonical,
        publishedAt=published_iso,
        language=language,
        rightsMode=rights_mode,
        imageCandidate=None,
        rawLocations=[],
    )
