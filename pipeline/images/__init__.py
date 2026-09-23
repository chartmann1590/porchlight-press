"""Licensed image attachment (Phase 4).

Re-exports the Commons provider and implements the priority chain +
relevance guard. See ``commons.py`` for the API/licensing details.
"""
from __future__ import annotations

import re
from typing import Any, Mapping

from ..rights import may_reuse_image
from .commons import CommonsProvider, clean_title

__all__ = [
    "CommonsProvider",
    "candidate_queries",
    "title_matches_query",
    "pick_commons_image",
    "enrich_stories",
]

_ENTITY_RE = re.compile(r"\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\b")
_STOPWORDS = {"the", "and", "for", "with", "from", "that", "this"}


def _humanize_metro(metro: str | None) -> str | None:
    if not metro:
        return None
    # "us-ny-capital-region" -> "Capital Region".
    parts = str(metro).split("-", 2)
    tail = parts[2] if len(parts) == 3 else str(metro)
    words = [w for w in tail.replace("_", " ").split() if w]
    return " ".join(w.capitalize() for w in words) or None


def candidate_queries(story: Mapping[str, Any], max_n: int = 6) -> list[str]:
    """Ordered Commons search queries: places/entities NAMED in the story.

    Locations first (city, then named entities from the text, then
    county/metro) so the first confident title match wins. Never invents a
    query: everything comes from the story's locations or its own text.
    """
    queries: list[str] = []
    seen: set[str] = set()

    def _add(value: str | None) -> None:
        if not value:
            return
        text = str(value).strip()
        if len(text) < 3 or text.lower() in seen:
            return
        seen.add(text.lower())
        queries.append(text)

    locations = story.get("locations", []) or []
    if isinstance(locations, list) and locations:
        primary = locations[0] if isinstance(locations[0], Mapping) else {}
        _add(str(primary.get("city") or "") or None)
    # Named multi-word entities from headline/dek/body (e.g. "City Hall"
    # alone is too generic; the full "Schenectady City Hall" is kept).
    text = " ".join(
        str(story.get(key) or "") for key in ("headline", "dek", "body")
    )
    for match in _ENTITY_RE.findall(text):
        words = match.split()
        if 2 <= len(words) <= 5 and len(match) <= 60:
            _add(match)
        if len(queries) >= max_n:
            break
    if isinstance(locations, list) and locations:
        primary = locations[0] if isinstance(locations[0], Mapping) else {}
        _add(str(primary.get("admin2") or "") or None)
        _add(_humanize_metro(str(primary.get("metro") or "") or None))
    return queries[:max_n]


def title_matches_query(title: str, query: str) -> bool:
    """Relevance guard: every significant query word must appear in the title.

    Comparison is case-insensitive on alphanumeric tokens; the "File:" prefix
    and extension are ignored. A single-word query ("Albany") matches any
    Albany-titled file; a multi-word query ("Schenectady City Hall") needs all
    of its significant words. No match -> no image (text-only layout).
    """
    clean = clean_title(title).lower()
    if not clean or not query or not query.strip():
        return False
    title_tokens = set(re.findall(r"[a-z0-9]+", clean))
    query_tokens = [
        w
        for w in re.findall(r"[a-z0-9]+", query.lower())
        if len(w) >= 3 and w not in _STOPWORDS
    ]
    if not query_tokens:
        return False
    return all(tok in title_tokens for tok in query_tokens)


def _source_supplied_image(
    cluster: Mapping[str, Any] | None,
    sources_by_id: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any] | None:
    """Step (1)/(3): member imageCandidate where reuse is allowed.

    Requires BOTH ``rightsMode`` in (PUBLIC_DOMAIN, OPEN_LICENSE) and the
    registry ``imageRules.allowReuse`` flag. Attribution is honoured when
    ``requireAttribution`` is set. Returns None today (no provider sets
    imageCandidate) — the check stays so a future feed cannot leak a
    publisher photo through RSS_EXCERPT/METADATA/LINK rights.
    """
    if not cluster:
        return None
    members = cluster.get("members", []) or []
    for member in members:
        if not isinstance(member, Mapping):
            continue
        candidate = str(member.get("imageCandidate") or "").strip()
        if not candidate.startswith("https://"):
            continue
        rights = str(member.get("rightsMode") or "")
        if not may_reuse_image(rights):
            continue
        src = sources_by_id.get(str(member.get("sourceId") or ""), {})
        rules = src.get("imageRules") if isinstance(src, Mapping) else None
        if not isinstance(rules, Mapping) or not rules.get("allowReuse"):
            continue
        entry: dict[str, Any] = {
            "url": candidate,
            "attribution": f"File photo: {str(member.get('publisher') or 'Source')}",
        }
        if isinstance(src, Mapping) and src.get("name"):
            entry["attribution"] = (
                f"File photo via {src.get('name')}"
            )
        return entry
    return None


def pick_commons_image(
    story: Mapping[str, Any],
    provider: CommonsProvider,
    *,
    limit_per_query: int = 3,
) -> dict[str, Any] | None:
    """First Commons result whose title confidently matches a story query."""
    for query in candidate_queries(story):
        for candidate in provider.search(query, limit=limit_per_query):
            if title_matches_query(str(candidate.get("title") or ""), query):
                image: dict[str, Any] = {"url": candidate["url"], "attribution": candidate["attribution"]}
                for key in ("creator", "license", "licenseUrl", "sourceUrl"):
                    if candidate.get(key):
                        image[key] = candidate[key]
                return image
    return None


def enrich_stories(
    stories: list[dict[str, Any]],
    provider: CommonsProvider,
    *,
    clusters_by_id: Mapping[str, Mapping[str, Any]] | None = None,
    sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
    limit_per_query: int = 3,
) -> dict[str, int]:
    """Attach licensed images in place. Returns stats (never raises).

    Order per story: source-supplied (allowReuse) -> Commons -> none.
    Stories without a confident match keep no ``image`` key (text-only).
    Existing ``image`` entries are left untouched.
    """
    clusters_by_id = clusters_by_id or {}
    sources_by_id = sources_by_id or {}
    stats = {"stories": len(stories), "with_image": 0, "commons": 0, "supplied": 0, "none": 0}
    for story in stories:
        if not isinstance(story, dict) or story.get("image"):
            if isinstance(story, dict) and story.get("image"):
                stats["with_image"] += 1
            else:
                stats["none"] += 1
            continue
        event_id = str(story.get("id") or "")
        supplied = _source_supplied_image(clusters_by_id.get(event_id), sources_by_id)
        if supplied:
            story["image"] = supplied
            stats["with_image"] += 1
            stats["supplied"] += 1
            continue
        try:
            found = pick_commons_image(story, provider, limit_per_query=limit_per_query)
        except Exception:  # noqa: BLE001 - images never fail a run
            found = None
        if found:
            story["image"] = found
            stats["with_image"] += 1
            stats["commons"] += 1
        else:
            stats["none"] += 1
    return stats
