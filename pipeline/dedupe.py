"""Dedup: exact canonical-URL match + near-dup headline match in a 48 h window.

Portable: no GitHub-specific logic, no network. Operates on normalized item
dicts (pipeline/normalize.py output).
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Mapping
from urllib.parse import urlparse

from rapidfuzz import fuzz

DEFAULT_THRESHOLD = 85
DEFAULT_WINDOW_HOURS = 48

_PUNCT_RE = re.compile(r"[^a-z0-9\s]")
_WS_RE = re.compile(r"\s+")

# Small English stopword list for headline normalization. Kept inline so the
# module has no data-file dependency and stays deterministic offline.
STOPWORDS = frozenset({
    "a", "an", "the", "and", "or", "but", "of", "at", "on", "in", "to", "for",
    "with", "by", "from", "up", "out", "as", "is", "are", "was", "were",
    "be", "been", "after", "before", "over", "under", "into", "says", "say",
    "new",
})


def normalize_headline(text: str | None) -> str:
    """Lowercase, strip punctuation/stopwords for fuzzy comparison."""
    if not text:
        return ""
    lowered = text.lower()
    cleaned = _PUNCT_RE.sub(" ", lowered)
    tokens = [t for t in _WS_RE.sub(" ", cleaned).split() if t and t not in STOPWORDS]
    return " ".join(tokens)


def headline_similarity(a: str | None, b: str | None) -> float:
    """RapidFuzz token_set_ratio (0-100) on normalized headlines."""
    na, nb = normalize_headline(a), normalize_headline(b)
    if not na or not nb:
        return 0.0
    return float(fuzz.token_set_ratio(na, nb))


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    if isinstance(value, datetime):
        dt = value
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def _sort_key(item: Mapping[str, Any]) -> tuple[str, str]:
    return (str(item.get("publishedAt") or ""), str(item.get("url") or ""))


def _canonical_host(url: Any) -> str:
    try:
        return (urlparse(str(url or "")).hostname or "").lower()
    except ValueError:
        return ""


def _same_source_or_host(a: Mapping[str, Any], b: Mapping[str, Any]) -> bool:
    """Share a registry source or a canonical URL host."""
    sa = str(a.get("sourceId") or "").strip().lower()
    sb = str(b.get("sourceId") or "").strip().lower()
    if sa and sa == sb:
        return True
    ha, hb = _canonical_host(a.get("url")), _canonical_host(b.get("url"))
    return bool(ha and ha == hb)


def deduplicate(
    items: list[Mapping[str, Any]],
    *,
    threshold: float = DEFAULT_THRESHOLD,
    window_hours: float = DEFAULT_WINDOW_HOURS,
) -> tuple[list[dict[str, Any]], dict[str, list[dict[str, Any]]]]:
    """Split items into uniques + duplicates.

    - Exact: canonical URL (or id) match is always a duplicate.
    - Near-dup: normalized headline token_set_ratio >= threshold within
      window_hours (pairwise publishedAt difference). When either item
      lacks a timestamp, near-dup matching additionally requires the same
      sourceId or canonical host.

    Deterministic: input is sorted by (publishedAt, url) first, so the same
    batch always keeps the same representative. The earliest item wins.

    Returns (unique_items, dup_groups) where dup_groups maps kept item id
    -> list of duplicate item dicts.
    """
    ordered = sorted([dict(i) for i in items], key=_sort_key)
    unique: list[dict[str, Any]] = []
    by_url: dict[str, dict[str, Any]] = {}
    by_id: dict[str, dict[str, Any]] = {}
    dup_groups: dict[str, list[dict[str, Any]]] = {}

    for item in ordered:
        url = str(item.get("url") or "")
        iid = str(item.get("id") or url)
        # Exact: same canonical URL or same id.
        kept = by_url.get(url) if url else None
        if kept is None and iid:
            kept = by_id.get(iid)
        if kept is not None:
            dup_groups.setdefault(str(kept.get("id") or kept.get("url")), []).append(item)
            continue
        # Near-dup: compare against kept items within the time window.
        # When either timestamp is missing there is no temporal evidence,
        # so only match within the same source or canonical host.
        match: dict[str, Any] | None = None
        item_time = _parse_time(item.get("publishedAt"))
        for cand in unique:
            cand_time = _parse_time(cand.get("publishedAt"))
            if item_time and cand_time:
                delta_h = abs((item_time - cand_time).total_seconds()) / 3600.0
                if delta_h > window_hours:
                    continue
            elif not _same_source_or_host(item, cand):
                continue
            sim = headline_similarity(
                str(item.get("headline") or ""), str(cand.get("headline") or "")
            )
            if sim >= threshold:
                match = cand
                break
        if match is not None:
            dup_groups.setdefault(str(match.get("id") or match.get("url")), []).append(item)
            continue
        unique.append(item)
        if url:
            by_url[url] = item
        if iid:
            by_id[iid] = item

    return unique, dup_groups
