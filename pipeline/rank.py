"""Ranking + confidence (internal only, never shown as a truth score).

score = w1*freshness + w2*locality + w3*corroboration + w4*sourcePriority
        + w5*breaking + w6*categoryBoost - stalePenalty - duplicatePenalty

- No ideology or political-lean features, ever. The only publisher-derived
  inputs are registry priority (0-100, operator-set) and official-type
  (nws-alerts/json-api/PUBLIC_DOMAIN). Publisher names are never scored.
- Per-user interest weighting happens on-device (Phase 8); this ranks only
  on objective factors.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping

DEFAULT_WEIGHTS = {
    "freshness": 0.25,
    "locality": 0.25,
    "corroboration": 0.20,
    "sourcePriority": 0.15,
    "breaking": 0.10,
    "categoryBoost": 0.05,
}
DEFAULT_BREAKING_MIN_SOURCES = 3
DEFAULT_BREAKING_WINDOW_MIN = 60
DEFAULT_STALE_PENALTY_PER_DAY = 0.05
DEFAULT_STALE_GRACE_HOURS = 24
DEFAULT_DUPLICATE_PENALTY = 0.10

LOCALITY_SCORES = {
    "local": 1.0,      # same city
    "regional": 0.8,   # same county/metro
    "state": 0.6,      # same admin1
    "national": 0.4,
    "world": 0.2,
}


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    try:
        dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def freshness_score(newest: datetime | None, now: datetime | None) -> float:
    """Linear decay 1.0 -> 0 over 72 h. Monotone: fresher >= older."""
    if not newest:
        return 0.0
    moment = now or datetime.now(timezone.utc)
    age_h = max(0.0, (moment - newest).total_seconds() / 3600.0)
    return max(0.0, 1.0 - age_h / 72.0)


def locality_score(section: str | None) -> float:
    return LOCALITY_SCORES.get(str(section or "").lower(), 0.2)


def corroboration_score(n_independent: int) -> float:
    """0 for solo, 0.5 for two, 1.0 for three or more independent publishers."""
    if n_independent <= 1:
        return 0.0
    return min(1.0, (n_independent - 1) / 2.0)


def source_priority_score(priorities: list[int]) -> float:
    if not priorities:
        return 0.5
    return max(0, min(100, max(priorities))) / 100.0


def category_boost_score(category: str | None, section: str | None) -> float:
    cat = str(category or "").lower()
    sec = str(section or "").lower()
    if cat in ("public-safety", "weather") and sec == "local":
        return 1.0
    if cat in ("public-safety", "weather"):
        return 0.5
    return 0.0


def independent_publishers(members: list[Mapping[str, Any]]) -> list[str]:
    """Distinct publisher identities (sourceId preferred, publisher fallback)."""
    seen: list[str] = []
    for m in members:
        key = str(m.get("sourceId") or m.get("publisher") or "").strip().lower()
        if key and key not in seen:
            seen.append(key)
    return seen


def is_official_source(source: Mapping[str, Any] | None) -> bool:
    """Government/official feeds: NWS, USGS-style JSON APIs, PUBLIC_DOMAIN."""
    if not source:
        return False
    if str(source.get("type") or "") in ("nws-alerts", "json-api"):
        return True
    if str(source.get("rightsMode") or "") == "PUBLIC_DOMAIN":
        return True
    name = f"{source.get('name') or ''} {source.get('id') or ''}".lower()
    return any(k in name for k in ("national weather service", "usgs", "city of", "county of"))


def is_breaking(
    members: list[Mapping[str, Any]],
    *,
    category: str | None = None,
    min_sources: int = DEFAULT_BREAKING_MIN_SOURCES,
    window_minutes: int = DEFAULT_BREAKING_WINDOW_MIN,
    has_official: bool = False,
) -> bool:
    """Exceptional corroboration in a tight window.

    - >= min_sources independent publishers within window_minutes -> breaking
      (lets exceptional national/world stories reach the top slot), OR
    - >= 2 independent publishers within the window with a public-safety /
      weather topic and an official source present (local emergencies).
    """
    pubs = independent_publishers(members)
    if len(pubs) < 2:
        return False
    times = sorted(t for t in (_parse_time(m.get("publishedAt")) for m in members) if t)
    if len(times) < 2:
        return False
    # Tightest window covering min_sources items (or 2 for the official path).
    span_min: float | None = None
    need = min(min_sources, len(times))
    for i in range(len(times) - need + 1):
        span = (times[i + need - 1] - times[i]).total_seconds() / 60.0
        span_min = span if span_min is None else min(span_min, span)
    if span_min is not None and len(pubs) >= min_sources and span_min <= window_minutes:
        return True
    if has_official and len(pubs) >= 2 and str(category or "").lower() in ("public-safety", "weather"):
        two_span = min(
            (times[i + 1] - times[i]).total_seconds() / 60.0 for i in range(len(times) - 1)
        )
        if two_span <= window_minutes:
            return True
    return False


def confidence_for(
    members: list[Mapping[str, Any]],
    sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
) -> str:
    """HIGH / MEDIUM / LOW / UNVERIFIED (internal; gates Phase 3 AI publish).

    - HIGH: >= 2 independent publishers, or official + independent.
    - MEDIUM: single trusted source (priority >= 70).
    - LOW: single source below that bar.
    - UNVERIFIED: single LINK_ONLY source (may join a cluster, never the
      basis of a brief). Maps to "low" in story-schema output.
    """
    sources_by_id = sources_by_id or {}
    pubs = independent_publishers(members)
    officials = 0
    for m in members:
        src = sources_by_id.get(str(m.get("sourceId") or ""))
        if is_official_source(src):
            officials += 1
            break
    if len(pubs) >= 2:
        return "HIGH"
    if officials and len(members) >= 2:
        return "HIGH"  # official + independent (wording from the plan)
    if len(members) == 1:
        only = members[0]
        if str(only.get("rightsMode") or "") == "LINK_ONLY":
            return "UNVERIFIED"
        src = sources_by_id.get(str(only.get("sourceId") or ""))
        try:
            prio = int(src.get("priority", 50)) if src else 50  # type: ignore[union-attr]
        except (TypeError, ValueError):
            prio = 50
        return "MEDIUM" if prio >= 70 else "LOW"
    # Multiple items, one publisher: not corroborated.
    sole_rights = {str(m.get("rightsMode") or "") for m in members}
    if sole_rights == {"LINK_ONLY"}:
        return "UNVERIFIED"
    return "LOW"


def score_cluster(
    cluster: Mapping[str, Any],
    sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
    *,
    now: datetime | None = None,
    weights: Mapping[str, float] | None = None,
    breaking_min_sources: int = DEFAULT_BREAKING_MIN_SOURCES,
    breaking_window_minutes: int = DEFAULT_BREAKING_WINDOW_MIN,
    stale_penalty_per_day: float = DEFAULT_STALE_PENALTY_PER_DAY,
    stale_grace_hours: float = DEFAULT_STALE_GRACE_HOURS,
    duplicate_penalty: float = DEFAULT_DUPLICATE_PENALTY,
    n_duplicates: int = 0,
) -> dict[str, Any]:
    """Score one cluster; returns {score, breaking, components}."""
    w = dict(DEFAULT_WEIGHTS)
    if weights:
        w.update(weights)
    sources_by_id = sources_by_id or {}
    members = list(cluster.get("members", []))
    section = str(cluster.get("section") or "world")
    category = str(cluster.get("category") or "local")

    times = [t for t in (_parse_time(m.get("publishedAt")) for m in members) if t]
    newest = max(times) if times else None
    moment = now or datetime.now(timezone.utc)

    pubs = independent_publishers(members)
    priorities: list[int] = []
    has_official = False
    for m in members:
        src = sources_by_id.get(str(m.get("sourceId") or ""))
        if src:
            try:
                priorities.append(int(src.get("priority", 50)))  # type: ignore[union-attr]
            except (TypeError, ValueError):
                priorities.append(50)
            if is_official_source(src):
                has_official = True
        else:
            priorities.append(50)

    fresh = freshness_score(newest, moment)
    loc = locality_score(section)
    corr = corroboration_score(len(pubs))
    prio = source_priority_score(priorities)
    breaking = is_breaking(members, category=category,
                           min_sources=breaking_min_sources,
                           window_minutes=breaking_window_minutes,
                           has_official=has_official)
    cat = category_boost_score(category, section)

    stale = 0.0
    if newest:
        age_h = max(0.0, (moment - newest).total_seconds() / 3600.0)
        if age_h > stale_grace_hours:
            stale = min(0.3, ((age_h - stale_grace_hours) / 24.0) * stale_penalty_per_day)

    dup = 0.0
    if n_duplicates > 0 or (len(members) > 1 and len(pubs) == 1):
        dup = duplicate_penalty if n_duplicates else duplicate_penalty / 2

    score = (
        w["freshness"] * fresh
        + w["locality"] * loc
        + w["corroboration"] * corr
        + w["sourcePriority"] * prio
        + w["breaking"] * (1.0 if breaking else 0.0)
        + w["categoryBoost"] * cat
        - stale - dup
    )
    score = max(0.0, min(1.0, score))
    return {
        "score": score,
        "breaking": breaking,
        "components": {
            "freshness": fresh, "locality": loc, "corroboration": corr,
            "sourcePriority": prio, "breaking": 1.0 if breaking else 0.0,
            "categoryBoost": cat, "stalePenalty": stale, "duplicatePenalty": dup,
        },
    }
