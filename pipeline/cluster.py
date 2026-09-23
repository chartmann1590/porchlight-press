"""Clustering: greedy assignment of normalized items to open event clusters.

Features: TF-IDF cosine over headline+excerpt, shared location, shared
capitalized entities, time proximity. Deterministic: items are sorted first,
so the same input order always yields the same output.

Stable IDs: eventId = sha256(canonical URL of the seed item)[:16]. The seed
is the earliest member (sorted input => first member). When clusters merge,
the older ID survives and the other is recorded as an alias.
"""
from __future__ import annotations

import hashlib
import logging
import re
from datetime import datetime, timezone
from typing import Any, Mapping

logger = logging.getLogger(__name__)

DEFAULT_WINDOW_HOURS = 72
DEFAULT_THRESHOLD = 0.45
DEFAULT_MERGE_THRESHOLD = 0.65
DEFAULT_WEIGHTS = {"tfidf": 0.55, "location": 0.20, "entity": 0.15, "time": 0.10}

_ENTITY_RE = re.compile(r"\b([A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,})*)\b")
_ENTITY_STOP = frozenset({
    "The", "This", "That", "These", "Those", "With", "From", "After",
    "Before", "Crews", "Fire", "Police",
})


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


def entity_similarity(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    inter = a & b
    if not inter:
        return 0.0
    # Any shared multi-word entity is strong; otherwise partial credit.
    for ent in inter:
        if " " in ent:
            return 1.0
    return min(1.0, 0.5 + 0.25 * (len(inter) - 1))


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
                ent = entity_similarity(ent_sets[idx], ent_sets[m])
                t = time_similarity(times[idx], times[m], window_hours)
                score = _combined_score(float(cos[idx][m]), loc, ent, t, w)
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
            ent = entity_similarity(extract_entities(docs[i]), extract_entities(docs[j]))
            t = time_similarity(_parse_time(a.get("lastSeen")), _parse_time(b.get("lastSeen")), window_hours)
            score = _combined_score(float(cos[i][j]), loc, ent, t, w)
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
