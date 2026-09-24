"""Cluster state: new / updated / unchanged, versions, aliases, pruning.

State file (default state/clusters.json):
  {"generatedAt": ISO, "clusters": {eventId: record}}
Legacy list format {"clusters": [...]} is also read.

Record: eventId, members (item dicts), memberIds, aliases, version, status,
firstSeen, lastSeen, headline, locations, section, category, score, breaking,
confidence, sources (provenance), lastBriefHash, lastGeneratedAt,
lastBrief, lastBriefFingerprint, lastBriefModel.

Accepted AI briefs persist inside their cluster record (lastBrief +
lastBriefFingerprint + lastBriefModel) so the next run can reuse them
without a model call when the source content is unchanged. Briefs expire
with their cluster via the pruneDays rule; no separate store exists.

- new: eventId (or alias) unseen -> version 1.
- updated: seen before AND a new independent/official source joined.
  Version increments at most once per run (one process call = one run).
- unchanged: otherwise; version and brief fields preserved.
- Every version keeps its source list (provenance stored on the record).
- Prune clusters with lastSeen older than pruneDays (default 7).
- Portable: plain JSON files, no CI env vars.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping


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


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def load_state(path: str | Path) -> dict[str, dict[str, Any]]:
    """Load eventId -> record. Missing/corrupt file -> {} (fresh start)."""
    p = Path(path)
    if not p.exists():
        return {}
    try:
        payload = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if isinstance(payload, dict) and isinstance(payload.get("clusters"), dict):
        clusters = payload["clusters"]
        return {str(k): dict(v) for k, v in clusters.items() if isinstance(v, dict)}
    if isinstance(payload, dict) and isinstance(payload.get("clusters"), list):
        out: dict[str, dict[str, Any]] = {}
        for rec in payload["clusters"]:
            if isinstance(rec, dict) and rec.get("eventId"):
                out[str(rec["eventId"])] = dict(rec)
        return out
    if isinstance(payload, dict):
        # Bare eventId -> record mapping.
        return {str(k): dict(v) for k, v in payload.items() if isinstance(v, dict)}
    return {}


def save_state(path: str | Path, clusters: Mapping[str, Mapping[str, Any]]) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    payload = {"generatedAt": _now_iso(),
               "clusters": {k: dict(v) for k, v in clusters.items()}}
    p.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _source_keys(members: list[Mapping[str, Any]]) -> set[str]:
    keys: set[str] = set()
    for m in members:
        key = str(m.get("sourceId") or m.get("publisher") or m.get("url") or "").strip().lower()
        if key:
            keys.add(key)
    return keys


def _official_keys(members: list[Mapping[str, Any]],
                   sources_by_id: Mapping[str, Mapping[str, Any]]) -> set[str]:
    from .rank import is_official_source

    keys: set[str] = set()
    for m in members:
        src = sources_by_id.get(str(m.get("sourceId") or ""))
        if is_official_source(src):
            key = str(m.get("sourceId") or m.get("publisher") or "").strip().lower()
            if key:
                keys.add(key)
    return keys


def cluster_content_fingerprint(cluster: Mapping[str, Any]) -> str:
    """Fingerprint of a cluster's source content for brief reuse.

    Covers member ids/urls + headlines/excerpts (+ publishedAt, which marks
    an updated source). Sorted by id/url so member order never triggers a
    false change. Returns a short hex digest; small enough to persist in the
    cluster record alongside the accepted brief.
    """
    entries: list[dict[str, str]] = []
    for m in (cluster.get("members", []) or []):
        if not isinstance(m, Mapping):
            continue
        entries.append({
            "id": str(m.get("id") or ""),
            "sourceId": str(m.get("sourceId") or ""),
            "url": str(m.get("url") or ""),
            "headline": str(m.get("headline") or ""),
            "excerpt": str(m.get("excerpt") or ""),
            "publishedAt": str(m.get("publishedAt") or ""),
        })
    entries.sort(key=lambda e: (
        e["id"], e["sourceId"], e["url"],
        e["headline"], e["excerpt"], e["publishedAt"],
    ))
    canonical = json.dumps(entries, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]


def stored_brief_usable(
    cluster: Mapping[str, Any], fingerprint: str | None = None,
) -> bool:
    """True when the cluster carries a stored accepted brief whose stored
    fingerprint matches its current source content.

    Pass a precomputed ``cluster_content_fingerprint`` to avoid hashing the
    members twice when the caller already holds it.
    """
    brief = cluster.get("lastBrief")
    if not isinstance(brief, dict) or not brief.get("headline") or not brief.get("body"):
        return False
    stored = str(cluster.get("lastBriefFingerprint") or "")
    if not stored:
        return False
    if fingerprint is None:
        fingerprint = cluster_content_fingerprint(cluster)
    return stored == fingerprint


def update_state(
    prev: Mapping[str, Mapping[str, Any]],
    new_clusters: list[Mapping[str, Any]],
    sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
    *,
    now: datetime | None = None,
    prune_days: float = 7,
) -> dict[str, dict[str, Any]]:
    """Merge fresh clusters into persistent state; assign status/version.

    Alias-aware: if a new eventId matches a previous alias, it resolves to
    the surviving ID. If one new cluster overlaps several previous clusters
    (members in common), the oldest previous ID survives and the rest become
    aliases.
    """
    sources_by_id = sources_by_id or {}
    moment = now or datetime.now(timezone.utc)
    # Alias -> surviving eventId from previous state.
    alias_to_id: dict[str, str] = {}
    for eid, rec in prev.items():
        alias_to_id[eid] = eid
        for al in rec.get("aliases", []) or []:
            alias_to_id.setdefault(str(al), eid)

    # Index previous member item ids -> eventId for overlap merges.
    member_to_prev: dict[str, str] = {}
    for eid, rec in prev.items():
        for mid in rec.get("memberIds", []) or []:
            member_to_prev.setdefault(str(mid), eid)

    updated: dict[str, dict[str, Any]] = {}
    for cluster in new_clusters:
        rec = dict(cluster)
        eid = str(rec.get("eventId"))
        # Resolve through previous aliases (merge survival).
        resolved = alias_to_id.get(eid, eid)
        # Overlap: members seen in other previous clusters => oldest wins.
        prev_hits: dict[str, datetime | None] = {}
        for mid in rec.get("memberIds", []) or []:
            hit = member_to_prev.get(str(mid))
            if hit:
                prev_rec = prev.get(hit, {})
                prev_hits[hit] = _parse_time(prev_rec.get("firstSeen"))
        candidates = set([resolved] + list(prev_hits.keys()))
        candidates = {c for c in candidates if c in prev or c == resolved}
        if len(prev_hits) > 1 or (resolved in prev and any(h != resolved for h in prev_hits)):
            # Merge: oldest firstSeen survives. Missing/unparseable dates
            # sort as newest (datetime.max) so they never win "oldest".
            def _age_key(cid: str) -> tuple[datetime, str]:
                r = prev.get(cid, {})
                dt = _parse_time(r.get("firstSeen"))
                if dt is None:
                    return (datetime.max.replace(tzinfo=timezone.utc), cid)
                return (dt, cid)
            survivor = min(candidates, key=_age_key)
        else:
            survivor = resolved if resolved in prev else (
                next(iter(prev_hits)) if len(prev_hits) == 1 and eid not in prev else eid
            )
            # Fresh ID that overlaps exactly one prev cluster adopts that ID.
            if eid not in prev and len(prev_hits) == 1:
                survivor = next(iter(prev_hits))

        prev_rec = prev.get(survivor)
        if prev_rec is None:
            rec["eventId"] = eid
            rec["status"] = "new"
            rec["version"] = 1
            rec.setdefault("aliases", [])
            rec.setdefault("lastBriefHash", None)
            rec.setdefault("lastGeneratedAt", None)
            rec.setdefault("lastBrief", None)
            rec.setdefault("lastBriefFingerprint", None)
            rec.setdefault("lastBriefModel", None)
            updated[eid] = rec
            continue

        # Existing event: new independent or official source?
        old_members = list(prev_rec.get("members", []) or [])
        old_keys = _source_keys(old_members)
        new_keys = _source_keys(list(rec.get("members", []) or []))
        joined = new_keys - old_keys
        old_official = _official_keys(old_members, sources_by_id)
        new_official = _official_keys(list(rec.get("members", []) or []), sources_by_id)
        official_joined = bool(new_official - old_official)

        # Merge aliases when the survivor absorbed other IDs.
        aliases = list(prev_rec.get("aliases", []) or [])
        for cid in candidates:
            if cid != survivor and cid not in aliases:
                aliases.append(cid)
        if eid != survivor and eid not in aliases:
            aliases.append(eid)
        for al in rec.get("aliases", []) or []:
            if al != survivor and al not in aliases:
                aliases.append(al)

        rec["eventId"] = survivor
        rec["aliases"] = aliases
        if joined or official_joined:
            rec["status"] = "updated"
            try:
                rec["version"] = int(prev_rec.get("version", 1)) + 1
            except (TypeError, ValueError):
                rec["version"] = 2
        else:
            rec["status"] = "unchanged"
            rec["version"] = prev_rec.get("version", 1)
        # Phase 3 owns brief fields; Phase 2 never clears them. Stored
        # briefs (with their fingerprints) carry forward so the newsroom can
        # reuse them when the source content is unchanged; they expire with
        # the cluster via pruning below.
        rec["lastBriefHash"] = prev_rec.get("lastBriefHash")
        rec["lastGeneratedAt"] = prev_rec.get("lastGeneratedAt")
        rec["lastBrief"] = prev_rec.get("lastBrief")
        rec["lastBriefFingerprint"] = prev_rec.get("lastBriefFingerprint")
        rec["lastBriefModel"] = prev_rec.get("lastBriefModel")
        updated[survivor] = rec

    # Carry forward previous clusters absent from this batch (still active),
    # marked unchanged so Phase 3 can keep their briefs.
    for eid, rec in prev.items():
        if eid not in updated and not any(eid in r.get("aliases", []) for r in updated.values()):
            carry = dict(rec)
            carry["status"] = "unchanged"
            updated[eid] = carry

    # Prune clusters older than prune_days (by lastSeen).
    pruned: dict[str, dict[str, Any]] = {}
    for eid, rec in updated.items():
        last = _parse_time(rec.get("lastSeen"))
        if last and (moment - last).total_seconds() / 86400.0 > prune_days:
            continue
        pruned[eid] = rec
    return pruned
