"""Location classification: source coverage (baseline) + gazetteer mentions (boost).

- Gazetteer: pipeline/geo/places.json (trimmed, committed) built by
  scripts/build_gazetteer.py from Census (PD) + GeoNames (CC BY 4.0).
  See docs/GEO_DATA_SOURCES.md. Full CI-time builds are loaded when the
  caller passes places_path pointing outside the repo.
- Coordinates are NEVER derived from gazetteer centroids. lat/lon appear on
  a location only when the source item itself supplied them (currently no
  provider does; the pass-through is kept for future municipal feeds).
- Section mapping (plan-literal, with one worldwide clarification):
  city -> local, admin2/metro -> regional, admin1 -> state,
  country US -> national, else world (non-US country or no location).
  The "else world" clarification keeps BBC-World-style items out of national.
"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

GEO_DIR = Path(__file__).resolve().parent / "geo"
DEFAULT_PLACES_PATH = GEO_DIR / "places.json"

SECTION_ORDER = {"local": 0, "regional": 1, "state": 2, "national": 3, "world": 4}

_CATEGORY_KEYWORDS: list[tuple[str, list[str]]] = [
    ("weather", ["tornado", "hurricane", "blizzard", "snowstorm", "flood warning",
                 "severe weather", "heat advisory", "winter storm", "nws", "forecast"]),
    ("public-safety", ["fire", "firefighters", "police", "shooting", "stabbing",
                        "crash", "evacuation", "emergency", "alert", "road closure",
                        "closure", "hazmat", "rescue", "ambulance"]),
    ("politics", ["council", "vote", "election", "mayor", "governor", "senate",
                  "assembly", "bill", "budget", "referendum", "ballot"]),
    ("sports", ["game", "team", "season", "playoff", "score", "coach", "league"]),
    ("business", ["business", "jobs", "economy", "market", "company", "factory",
                   "downtown", "development"]),
    ("health", ["health", "hospital", "clinic", "disease", "vaccine", "outbreak"]),
    ("environment", ["climate", "river", "park", "wildlife", "pollution", "solar"]),
    ("technology", ["tech", "software", "ai ", "startup", "cyber", "app "]),
    ("science", ["earthquake", "study", "research", "nasa", "scientists", "magnitude"]),
    ("entertainment", ["concert", "festival", "film", "music", "theater", "art "]),
    ("travel", ["travel", "flight", "airport", "amtrak", "highway", "thruway"]),
]


def classify_category(headline: str | None, excerpt: str | None,
                      source_type: str | None = None) -> str:
    """Heuristic taxonomy id. NWS -> weather, USGS/json-api quakes -> science.

    Default is "local" (town news with no topical keywords). Single-label by
    design; Phase 3 may refine it, never inventing a label outside taxonomy.
    """
    if source_type == "nws-alerts":
        return "weather"
    text = f"{headline or ''} {excerpt or ''}".lower()
    if "earthquake" in text or "magnitude" in text or "m " in text[:8].lower():
        # USGS items carry "M 5.1 - ..." titles; keep them science.
        if source_type == "json-api":
            return "science"
    for cat, keywords in _CATEGORY_KEYWORDS:
        for kw in keywords:
            if kw in text:
                return cat
    return "local"


def load_places(path: str | Path | None = None) -> list[dict[str, Any]]:
    p = Path(path) if path else DEFAULT_PLACES_PATH
    try:
        payload = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    if isinstance(payload, dict):
        return list(payload.get("places", []))
    return list(payload)


@lru_cache(maxsize=4)
def _cached_places(path_str: str) -> tuple[tuple[tuple[str, Any], ...], ...]:
    places = load_places(path_str or str(DEFAULT_PLACES_PATH))
    # Hashable snapshot for the cache; callers get fresh dicts via load_places.
    return tuple(tuple(sorted((k, json.dumps(v, sort_keys=True)) for k, v in p.items())) for p in places)


def _place_names(place: Mapping[str, Any]) -> list[str]:
    names = [str(place.get("name") or "")]
    for alias in place.get("aliases") or []:
        names.append(str(alias))
    return [n for n in names if n]


def find_place_mentions(text: str, places: list[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Gazetteer mentions in text, longest-name-first, no double counting."""
    if not text:
        return []
    lowered = text.lower()
    # Longest names first so "Saratoga Springs" wins over "Saratoga".
    ranked: list[tuple[int, dict[str, Any], str]] = []
    for place in places:
        for name in _place_names(place):
            if len(name) < 3:
                continue
            ranked.append((-len(name), dict(place), name))
    ranked.sort(key=lambda t: t[0])
    hits: list[dict[str, Any]] = []
    claimed_spans: list[tuple[int, int]] = []
    for _, place, name in ranked:
        pattern = r"\b" + re.escape(name.lower()) + r"\b"
        for m in re.finditer(pattern, lowered):
            span = (m.start(), m.end())
            if any(s < span[1] and span[0] < e for s, e in claimed_spans):
                continue
            claimed_spans.append(span)
            hits.append(place)
            break  # one hit per place name is enough
    # Dedupe by place name, keep order (longest first => most specific first).
    seen: set[str] = set()
    out: list[dict[str, Any]] = []
    for h in hits:
        key = str(h.get("name"))
        if key not in seen:
            seen.add(key)
            out.append(h)
    return out


def _location_from_place(place: Mapping[str, Any]) -> dict[str, Any]:
    loc: dict[str, Any] = {"country": str(place.get("country") or "US")}
    if place.get("admin1"):
        loc["admin1"] = str(place["admin1"])
    if place.get("admin2"):
        loc["admin2"] = str(place["admin2"])
    if place.get("city"):
        loc["city"] = str(place["city"])
    if place.get("metro"):
        loc["metro"] = str(place["metro"])
    return loc


def _location_from_coverage(coverage: Mapping[str, Any] | None) -> dict[str, Any]:
    cov = coverage or {}
    loc: dict[str, Any] = {"country": str(cov.get("country") or "US")}
    if cov.get("admin1"):
        loc["admin1"] = str(cov["admin1"])
    admin2 = cov.get("admin2") or []
    if isinstance(admin2, list) and admin2:
        loc["admin2"] = str(admin2[0])
    cities = cov.get("cities") or []
    if isinstance(cities, list) and cities:
        loc["city"] = str(cities[0])
    if cov.get("metro"):
        loc["metro"] = str(cov["metro"])
    return loc


def section_for_location(loc: Mapping[str, Any]) -> str:
    if loc.get("city"):
        return "local"
    if loc.get("admin2") or loc.get("metro"):
        return "regional"
    if loc.get("admin1"):
        return "state"
    if str(loc.get("country") or "").upper() == "US":
        return "national"
    return "world"


def locate_item(
    item: Mapping[str, Any],
    source: Mapping[str, Any] | None = None,
    places: list[Mapping[str, Any]] | None = None,
    places_path: str | Path | None = None,
) -> dict[str, Any]:
    """Return {locations, section, locality, category} for one item.

    - Baseline: the source registry coverage (so every item has ≥1 location).
    - Boost: gazetteer place names found in headline+excerpt. The most
      specific mention becomes the primary location; up to 3 distinct
      locations are kept.
    - Never invents coordinates: lat/lon pass through only if the item dict
      already carries them (no current provider does).
    """
    src = source or {}
    coverage = src.get("coverage") if isinstance(src.get("coverage"), dict) else {}
    baseline = _location_from_coverage(coverage)
    text = f"{item.get('headline') or ''} {item.get('excerpt') or ''}"
    gaz = places if places is not None else load_places(places_path)
    mentions = find_place_mentions(text, list(gaz))

    locations: list[dict[str, Any]] = []
    if mentions:
        # Most specific first: city > admin2/metro > admin1 > country.
        def _spec(p: Mapping[str, Any]) -> int:
            if p.get("city"):
                return 0
            if p.get("admin2") or p.get("metro"):
                return 1
            if p.get("admin1"):
                return 2
            return 3

        for place in sorted(mentions, key=_spec)[:3]:
            loc = _location_from_place(place)
            if loc not in locations:
                locations.append(loc)
        # If the baseline is more specific than every mention (e.g. source
        # covers a city but the headline names only the state), keep it too.
        if baseline not in locations and len(locations) < 3:
            # Only add the baseline when it adds granularity.
            if baseline.get("city") and not any(l.get("city") for l in locations):
                locations.append(dict(baseline))
    else:
        locations = [dict(baseline)]

    # Coordinate pass-through only (never gazetteer centroids).
    for loc in locations:
        for key in ("lat", "lon"):
            if item.get(key) is not None:
                try:
                    loc[key] = float(item[key])  # type: ignore[literal-required]
                except (TypeError, ValueError):
                    pass

    # Primary = most specific location.
    def _section_rank(loc: Mapping[str, Any]) -> int:
        return SECTION_ORDER[section_for_location(loc)]

    primary = min(locations, key=_section_rank) if locations else dict(baseline)
    section = section_for_location(primary)
    category = classify_category(
        str(item.get("headline") or ""), str(item.get("excerpt") or ""),
        str(src.get("type") or "") if src else None,
    )
    return {
        "locations": locations or [dict(baseline)],
        "section": section,
        "locality": section,  # server-side ladder key; per-user rerank is on-device (Phase 8)
        "category": category,
    }


def lookup_postal(code: str, path: str | Path | None = None) -> dict[str, Any] | None:
    """ZIP/postal -> place for onboarding pickers (Phase 5 data path)."""
    p = Path(path) if path else (GEO_DIR / "postal.json")
    try:
        payload = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    entries = payload.get("postal", []) if isinstance(payload, dict) else []
    want = str(code).strip().upper()
    for entry in entries:
        if str(entry.get("postal")).upper() == want:
            return dict(entry)
    # US ZIP+4 prefix fallback.
    if len(want) > 5:
        return lookup_postal(want[:5], path)
    return None
