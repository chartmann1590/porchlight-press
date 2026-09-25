"""Clustering: greedy assignment of normalized items to open event clusters.

Features: TF-IDF cosine over headline+excerpt, shared location, shared
capitalized entities, time proximity. Deterministic: items are sorted first,
so the same input order always yields the same output.

Anti-overmerge invariant: a shared place name alone must never merge two
items. Place-derived capitalized spans are excluded from entity evidence
from TWO sources: (1) city/county/metro/state names from either item's
resolved locations, and (2) the committed NY municipality list
(pipeline/geo/municipalities.json, Census Gazetteer PD), which covers town /
village / CDP names even when locate resolved only the metro (live Ballston
case: items located to Albany/Capital Region while prose names Ballston
Spa, absent from the trimmed gazetteer). A join additionally requires
genuine textual evidence -- TF-IDF cosine at/above MIN_TEXT_SIM alone, or a
shared entity PLUS cosine at/above MIN_ENTITY_TEXT_SIM. Location + recency
can only confirm a textual match, never create one.

Both cluster_items and maybe_merge_clusters exclude place entities through
the single shared helper place_aware_entity_similarity, so fresh clustering
and persisted revalidation (state.py, which calls these same functions) can
never disagree on what counts as event evidence.

Stable IDs: eventId = sha256(canonical URL of the seed item)[:16]. The seed
is the earliest member (sorted input => first member). When clusters merge,
the older ID survives and the other is recorded as an alias.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

logger = logging.getLogger(__name__)

GEO_DIR = Path(__file__).resolve().parent / "geo"
DEFAULT_MUNICIPALITIES_PATH = GEO_DIR / "municipalities.json"

DEFAULT_WINDOW_HOURS = 72
DEFAULT_THRESHOLD = 0.45
DEFAULT_MERGE_THRESHOLD = 0.65
DEFAULT_WEIGHTS = {"tfidf": 0.55, "location": 0.20, "entity": 0.15, "time": 0.10}
# Minimum TF-IDF cosine required when a pair shares no non-place entity.
# Calibrated: an unrelated same-city pair sharing only "Ballston Spa" scores
# ~0.15 even in a 40-document corpus, while true same-event pairs (Albany
# fire fixture) score >= 0.27. Location + recency (0.30 combined) can never
# reach the 0.45 join threshold on their own.
MIN_TEXT_SIM = 0.20
# Minimum TF-IDF cosine required even when a shared entity exists.
# Second layer behind the municipality gate below: with place entities
# excluded, the live Ballston pair has ent=0.0 in ANY corpus (2-doc or full),
# so the MIN_TEXT_SIM=0.20 floor blocks it (full-corpus cos=0.090) while true
# same-event pairs (Albany fire 0.23-0.33, Santa pair 0.31-0.41, award reword
# 0.35) pass with margin. Deliberately NOT raised to chase the 0.090: a
# corpus-dependent threshold is fragile, and heavily reworded same-event
# pairs (shared person name, cos 0.10-0.15) must keep merging.
MIN_ENTITY_TEXT_SIM = 0.08

_ENTITY_RE = re.compile(r"\b([A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,})*)\b")
_ENTITY_STOP = frozenset({
    "The", "This", "That", "These", "Those", "With", "From", "After",
    "Before", "Crews", "Fire", "Police",
})
# Trailing generics stripped to also match bare-name mentions ("Saratoga"
# for resolved admin2 "Saratoga County").
_PLACE_SUFFIX_RE = re.compile(
    r"\s+(county|city|town|township|village|borough|parish|state|"
    r"commonwealth|district|region|metro(?:politan area)?)$"
)
_PLACE_KEYS = ("city", "admin2", "metro", "admin1", "country")


def event_id_for_seed(seed_url: str) -> str:
    return hashlib.sha256(seed_url.encode("utf-8")).hexdigest()[:16]


def extract_entities(text: str | None) -> set[str]:
    """Capitalized spans (single or multi-word), lowercased for comparison."""
    if not text:
        return set()
    out: set[str] = set()
    for m in _ENTITY_RE.finditer(text):
        span = m.group(1).strip()
        if span in _ENTITY_STOP:
            continue
        out.add(span.lower())
    return out


def text_for_item(item: Mapping[str, Any]) -> str:
    head = str(item.get("headline") or "")
    excerpt = str(item.get("excerpt") or "")
    return f"{head} {excerpt}".strip()


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


def _location_key(loc: Mapping[str, Any]) -> tuple[str, str, str, str, str]:
    return (
        str(loc.get("country") or "").upper(),
        str(loc.get("admin1") or "").upper(),
        str(loc.get("admin2") or "").lower(),
        str(loc.get("city") or "").lower(),
        str(loc.get("metro") or "").lower(),
    )


def location_similarity(
    a_locs: list[Mapping[str, Any]] | None, b_locs: list[Mapping[str, Any]] | None
) -> float:
    """1.0 city match, 0.8 county/metro, 0.6 admin1, 0.4 country, else 0."""
    if not a_locs or not b_locs:
        return 0.0
    best = 0.0
    for a in a_locs:
        for b in b_locs:
            ak, bk = _location_key(a), _location_key(b)
            if ak[0] and ak[0] == bk[0]:
                score = 0.4
                if ak[1] and ak[1] == bk[1]:
                    score = 0.6
                if (ak[2] and ak[2] == bk[2]) or (ak[4] and ak[4] == bk[4]):
                    score = 0.8
                if ak[3] and ak[3] == bk[3]:
                    score = 1.0
                best = max(best, score)
    return best


def entity_similarity(
    a: set[str], b: set[str], *, ignore: frozenset[str] | set[str] = frozenset()
) -> float:
    if not a or not b:
        return 0.0
    inter = (a & b) - ignore
    if not inter:
        return 0.0
    # Any shared multi-word entity is strong; otherwise partial credit.
    for ent in inter:
        if " " in ent:
            return 1.0
    return min(1.0, 0.5 + 0.25 * (len(inter) - 1))


def _norm_place(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("-", " ").replace("_", " ")).strip().lower()


def place_names_for_item(item: Mapping[str, Any]) -> set[str]:
    """Normalized place phrases from an item's resolved locations.

    Covers display names (city, admin2), metro slugs (humanized tail, e.g.
    "us-ny-capital-region" -> "capital region"), admin1/country codes, and
    bare-name variants with trailing generics stripped ("Saratoga County"
    -> "saratoga"). Shared entities matching these are place evidence, not
    event evidence, and are excluded from entity similarity.
    """
    names: set[str] = set()
    locs = item.get("locations")
    if not isinstance(locs, list):
        return names
    for loc in locs:
        if not isinstance(loc, Mapping):
            continue
        for key in _PLACE_KEYS:
            raw = str(loc.get(key) or "").strip()
            if not raw:
                continue
            if key == "metro" and "-" in raw:
                # Slug form "cc-ss-<name>...": the humanized tail is what
                # prose uses ("Capital Region").
                parts = raw.split("-")
                tail = parts[2:] if len(parts) > 2 else parts
                human = _norm_place(" ".join(tail))
                if human:
                    names.add(human)
            norm = _norm_place(raw)
            if norm:
                names.add(norm)
                bare = _PLACE_SUFFIX_RE.sub("", norm).strip()
                if bare:
                    names.add(bare)
    return names


def has_genuine_overlap(tfidf_cos: float, ent: float) -> bool:
    """Non-place evidence gate: shared entity PLUS real textual overlap,
    or strong textual overlap alone. A shared place name with thin text
    never passes (live Ballston pair: ent=0.0 after the municipality gate,
    cos=0.090 < 0.20 in the full corpus, 0.052 in a 2-doc corpus)."""
    if tfidf_cos >= MIN_TEXT_SIM:
        return True
    return ent > 0.0 and tfidf_cos >= MIN_ENTITY_TEXT_SIM


@lru_cache(maxsize=4)
def _cached_municipality_names(path_str: str) -> frozenset[str]:
    """Normalized municipality names from the committed list (or override).

    Missing/corrupt file -> empty set (graceful fallback to resolved-location
    place exclusion only). Normalized with _norm_place, the same form used
    for extracted entities and resolved place names.
    """
    p = Path(path_str) if path_str else DEFAULT_MUNICIPALITIES_PATH
    try:
        payload = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return frozenset()
    raw = payload.get("names", []) if isinstance(payload, dict) else []
    return frozenset(
        _norm_place(str(n)) for n in raw if str(n).strip()
    )


def load_municipality_names(path: str | Path | None = None) -> frozenset[str]:
    """Public loader for the place-entity gate list (default: committed NY)."""
    key = str(path) if path else str(DEFAULT_MUNICIPALITIES_PATH)
    return _cached_municipality_names(key)


def place_aware_entity_similarity(
    a: set[str],
    b: set[str],
    *,
    ignore: frozenset[str] | set[str] = frozenset(),
    municipalities: frozenset[str] | set[str] | None = None,
) -> float:
    """Entity similarity with place names excluded from BOTH sources.

    `ignore` carries the resolved-location place phrases for the pair;
    `municipalities` (default: committed NY list) additionally strips any
    shared entity naming a municipality, whether or not locate resolved it.
    THE single choke point for event evidence: cluster_items,
    maybe_merge_clusters -- and therefore state.py revalidation -- all go
    through here, so the rules cannot disagree.
    """
    muni = load_municipality_names() if municipalities is None else municipalities
    extra = ((set(a) | set(b)) & set(muni)) if muni else set()
    return entity_similarity(a, b, ignore=set(ignore) | extra)


def place_names_for_members(members: list[Mapping[str, Any]]) -> set[str]:
    """Union of place phrases over cluster members (for merge decisions)."""
    names: set[str] = set()
    for m in members:
        if isinstance(m, Mapping):
            names |= place_names_for_item(m)
    return names


def time_similarity(a: datetime | None, b: datetime | None, window_hours: float) -> float:
    if not a or not b or window_hours <= 0:
        return 0.5 if (a is None or b is None) else 0.0
    delta_h = abs((a - b).total_seconds()) / 3600.0
    if delta_h >= window_hours:
        return 0.0
    return max(0.0, 1.0 - delta_h / window_hours)


def _tfidf_cosine(texts: list[str]):
    """Cosine similarity matrix (n x n) via TF-IDF unigrams+bigrams.

    Falls back to a zero matrix when texts are too small for a vocabulary
    (e.g. a single item or all stopwords).
    """
    import numpy as np
    from sklearn.feature_extraction.text import TfidfVectorizer

    if len(texts) < 2 or not any(t.strip() for t in texts):
        return np.zeros((len(texts), len(texts)))
    try:
        vec = TfidfVectorizer(stop_words="english", ngram_range=(1, 2), max_features=5000)
        mat = vec.fit_transform(texts)
        sim = (mat * mat.T).toarray()
        return sim
    except Exception as e:
        logger.exception("TF-IDF cosine computation failed: %s", e)
        return np.zeros((len(texts), len(texts)))


def _combined_score(
    tfidf_cos: float, loc: float, ent: float, t: float, weights: Mapping[str, float]
) -> float:
    return (
        weights.get("tfidf", 0.55) * tfidf_cos
        + weights.get("location", 0.20) * loc
        + weights.get("entity", 0.15) * ent
        + weights.get("time", 0.10) * t
    )


def cluster_items(
    items: list[Mapping[str, Any]],
    *,
    threshold: float = DEFAULT_THRESHOLD,
    window_hours: float = DEFAULT_WINDOW_HOURS,
    weights: Mapping[str, float] | None = None,
) -> list[dict[str, Any]]:
    """Greedy assignment against open clusters (last 72 h).

    Each item joins the best-scoring open cluster at/above threshold, else it
    seeds a new cluster. Open = cluster lastSeen within window_hours of the
    item's publishedAt (items without timestamps are always comparable).
    """
    w = dict(DEFAULT_WEIGHTS)
    if weights:
        w.update(weights)
    ordered = sorted(
        [dict(i) for i in items],
        key=lambda d: (str(d.get("publishedAt") or ""), str(d.get("url") or "")),
    )
    if not ordered:
        return []

    texts = [text_for_item(i) for i in ordered]
    cos = _tfidf_cosine(texts)
    times = [_parse_time(i.get("publishedAt")) for i in ordered]
    ent_sets = [extract_entities(f"{i.get('headline') or ''} {i.get('excerpt') or ''}") for i in ordered]
    place_names: list[set[str]] = [place_names_for_item(i) for i in ordered]
    loc_lists: list[list[Mapping[str, Any]]] = [
        list(i.get("locations") or []) if isinstance(i.get("locations"), list) else []
        for i in ordered
    ]

    clusters: list[dict[str, Any]] = []
    # member index lists parallel to clusters
    cluster_members: list[list[int]] = []

    for idx, item in enumerate(ordered):
        best_ci = -1
        best_score = -1.0
        for ci, members in enumerate(cluster_members):
            # Open-window check against the cluster's latest member time.
            last_time = max(
                (times[m] for m in members if times[m]),
                default=None,
            )
            if times[idx] and last_time and window_hours > 0:
                if abs((times[idx] - last_time).total_seconds()) / 3600.0 > window_hours:
                    continue
            # Max similarity to any member (permissive, good for 3-source fires).
            top = 0.0
            for m in members:
                loc = location_similarity(loc_lists[idx], loc_lists[m])
                ent = place_aware_entity_similarity(
                    ent_sets[idx], ent_sets[m],
                    ignore=place_names[idx] | place_names[m],
                )
                t = time_similarity(times[idx], times[m], window_hours)
                cos_im = float(cos[idx][m])
                if not has_genuine_overlap(cos_im, ent):
                    continue  # place + recency alone never joins
                score = _combined_score(cos_im, loc, ent, t, w)
                top = max(top, score)
            if top > best_score:
                best_score = top
                best_ci = ci
        if best_ci >= 0 and best_score >= threshold:
            cluster_members[best_ci].append(idx)
        else:
            seed_url = str(item.get("url") or item.get("id") or f"seed-{idx}")
            clusters.append({
                "eventId": event_id_for_seed(seed_url),
                "seedUrl": seed_url,
                "aliases": [],
                "version": 1,
            })
            cluster_members.append([idx])

    out: list[dict[str, Any]] = []
    for c, members in zip(clusters, cluster_members):
        mem_items = [ordered[m] for m in members]
        member_times = [times[m] for m in members if times[m]]
        c["members"] = mem_items
        c["memberIds"] = [str(i.get("id") or i.get("url")) for i in mem_items]
        c["firstSeen"] = min(member_times).isoformat().replace("+00:00", "Z") if member_times else None
        c["lastSeen"] = max(member_times).isoformat().replace("+00:00", "Z") if member_times else None
        out.append(c)
    # Deterministic order: by firstSeen then eventId.
    out.sort(key=lambda c: (c.get("firstSeen") or "", c.get("eventId") or ""))
    return out


def maybe_merge_clusters(
    clusters: list[dict[str, Any]],
    *,
    merge_threshold: float = DEFAULT_MERGE_THRESHOLD,
    window_hours: float = DEFAULT_WINDOW_HOURS,
    weights: Mapping[str, float] | None = None,
) -> list[dict[str, Any]]:
    """Collapse near-identical clusters; older eventId survives as the ID.

    Pairwise combined similarity on (TF-IDF centroid proxy, location, entity,
    time). Uses each cluster's concatenated member text as its document, so a
    merge decision sees the whole event, not one item. Records the retired ID
    in the survivor's aliases. Deterministic: oldest firstSeen wins ties.
    """
    w = dict(DEFAULT_WEIGHTS)
    if weights:
        w.update(weights)
    if len(clusters) < 2:
        return clusters
    # Oldest first so the survivor is deterministic.
    ordered = sorted(clusters, key=lambda c: (c.get("firstSeen") or "", c.get("eventId") or ""))
    docs = [" ".join(text_for_item(m) for m in c.get("members", [])) for c in ordered]
    cos = _tfidf_cosine(docs)
    alive: list[dict[str, Any] | None] = list(ordered)
    for i in range(len(ordered)):
        if alive[i] is None:
            continue
        for j in range(i + 1, len(ordered)):
            if alive[j] is None:
                continue
            a, b = alive[i], alive[j]
            assert a is not None and b is not None
            a_locs = [loc for m in a.get("members", []) for loc in (m.get("locations") or [])]
            b_locs = [loc for m in b.get("members", []) for loc in (m.get("locations") or [])]
            loc = location_similarity(a_locs, b_locs)
            ent = place_aware_entity_similarity(
                extract_entities(docs[i]), extract_entities(docs[j]),
                ignore=place_names_for_members(a.get("members", []))
                | place_names_for_members(b.get("members", [])),
            )
            t = time_similarity(_parse_time(a.get("lastSeen")), _parse_time(b.get("lastSeen")), window_hours)
            cos_ij = float(cos[i][j])
            if not has_genuine_overlap(cos_ij, ent):
                continue  # place + recency alone never merges
            score = _combined_score(cos_ij, loc, ent, t, w)
            if score >= merge_threshold:
                # Survivor = older firstSeen (ordered => i is older).
                a["members"] = list(a.get("members", [])) + list(b.get("members", []))
                a["memberIds"] = list(a.get("memberIds", [])) + list(b.get("memberIds", []))
                aliases = list(a.get("aliases", []))
                aliases.append(b.get("eventId"))
                aliases.extend(b.get("aliases", []))
                # Dedupe aliases preserving order, never alias self.
                seen: set[str] = set()
                clean: list[str] = []
                for al in aliases:
                    if al and al != a.get("eventId") and al not in seen:
                        seen.add(al)
                        clean.append(al)
                a["aliases"] = clean
                # Widen time bounds.
                times = [_parse_time(v) for v in (a.get("firstSeen"), a.get("lastSeen"),
                                                  b.get("firstSeen"), b.get("lastSeen"))]
                times = [t for t in times if t]
                if times:
                    a["firstSeen"] = min(times).isoformat().replace("+00:00", "Z")
                    a["lastSeen"] = max(times).isoformat().replace("+00:00", "Z")
                a["version"] = max(int(a.get("version", 1)), int(b.get("version", 1)))
                alive[j] = None
    return [c for c in alive if c is not None]
