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

Persisted-load repair (fix/revalidate-persisted-clusters): clusters loaded
from disk are scrubbed of U+FFFD and re-checked against the CURRENT merge
rules (same scoring as new matching). Members that no longer belong are
split off deterministically; split survivors drop their stored brief so it
regenerates. This heals pre-#12 place-only merges kept in pipeline-state.

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

_REPLACEMENT_CHAR = "\ufffd"


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


def _scrub_str(value: Any) -> Any:
    """Remove U+FFFD from a text field, preserving None/non-str as-is."""
    if value is None:
        return None
    if not isinstance(value, str):
        return value
    if _REPLACEMENT_CHAR not in value:
        return value
    from .normalize import strip_replacement_chars

    return strip_replacement_chars(value)


def _drop_brief(rec: dict[str, Any]) -> None:
    rec["lastBrief"] = None
    rec["lastBriefFingerprint"] = None
    rec["lastBriefModel"] = None
    rec["lastBriefHash"] = None
    rec["lastGeneratedAt"] = None


def _scrub_record(rec: dict[str, Any]) -> bool:
    """Scrub U+FFFD from persisted text fields in place.

    Covers cluster headline/excerpt, member headlines/excerpts, source
    provenance headlines, and the stored brief (headline/dek/body).
    If the stored brief itself contained U+FFFD, it is dropped so it
    regenerates. Returns True when any field changed.
    """
    changed = False
    for key in ("headline", "excerpt"):
        if key in rec and isinstance(rec[key], str) and _REPLACEMENT_CHAR in rec[key]:
            rec[key] = _scrub_str(rec[key])
            changed = True
    members = rec.get("members")
    if isinstance(members, list):
        for m in members:
            if not isinstance(m, dict):
                continue
            for key in ("headline", "excerpt"):
                if key in m and isinstance(m[key], str) and _REPLACEMENT_CHAR in m[key]:
                    m[key] = _scrub_str(m[key])
                    changed = True
    sources = rec.get("sources")
    if isinstance(sources, list):
        for s in sources:
            if not isinstance(s, dict):
                continue
            if isinstance(s.get("headline"), str) and _REPLACEMENT_CHAR in s["headline"]:
                s["headline"] = _scrub_str(s["headline"])
                changed = True
    brief = rec.get("lastBrief")
    if isinstance(brief, dict):
        had_fffd = any(
            isinstance(brief.get(k), str) and _REPLACEMENT_CHAR in str(brief.get(k))
            for k in ("headline", "dek", "body")
        )
        if had_fffd:
            _drop_brief(rec)
            changed = True
        else:
            for key in ("headline", "dek", "body"):
                if isinstance(brief.get(key), str) and _REPLACEMENT_CHAR in str(brief.get(key)):
                    brief[key] = _scrub_str(brief.get(key))
                    changed = True
    return changed


def _member_sort_key(m: Mapping[str, Any]) -> tuple[str, str, str]:
    return (
        str(m.get("publishedAt") or ""),
        str(m.get("url") or ""),
        str(m.get("id") or ""),
    )


def revalidate_persisted_clusters(
    prev: Mapping[str, Mapping[str, Any]],
    *,
    threshold: float | None = None,
    window_hours: float | None = None,
    weights: Mapping[str, float] | None = None,
    merge_threshold: float | None = None,
) -> dict[str, dict[str, Any]]:
    """Scrub U+FFFD and split stale place-only merges using current rules.

    Every record is scrubbed (see _scrub_record). Records with 2+ members
    are re-clustered with pipeline.cluster.cluster_items + maybe_merge_clusters
    (place-derived entities excluded, MIN_TEXT_SIM gate). If members still
    belong together, the record is kept (same eventId). Otherwise members are
    split: the sub-cluster containing the earliest member (anchor) keeps the
    original eventId/aliases, split-offs get stable event_id_for_seed ids.
    All split pieces drop their stored brief so it regenerates; the
    fingerprint check (cluster_content_fingerprint) would already mismatch,
    clearing makes the invalidation explicit. Deterministic: inputs sorted,
    outputs keyed by eventId.
    """
    from .cluster import (
        DEFAULT_MERGE_THRESHOLD,
        DEFAULT_THRESHOLD,
        DEFAULT_WINDOW_HOURS,
        cluster_items,
        event_id_for_seed,
        maybe_merge_clusters,
    )

    th = DEFAULT_THRESHOLD if threshold is None else float(threshold)
    wh = DEFAULT_WINDOW_HOURS if window_hours is None else float(window_hours)
    mth = DEFAULT_MERGE_THRESHOLD if merge_threshold is None else float(merge_threshold)

    out: dict[str, dict[str, Any]] = {}
    # Deterministic input order.
    ordered_prev = sorted(prev.items(), key=lambda kv: (str(kv[1].get("firstSeen") or ""), str(kv[0])))
    for eid, raw in ordered_prev:
        rec: dict[str, Any] = dict(raw)
        rec["eventId"] = str(rec.get("eventId") or eid)
        # Ensure members/memberIds are present lists.
        members = [dict(m) for m in (rec.get("members") or []) if isinstance(m, Mapping)]
        _scrub_record(rec)
        # Re-read members after scrub (same objects, scrubbed in place).
        members = [dict(m) for m in (rec.get("members") or []) if isinstance(m, dict)]
        if len(members) < 2:
            rec["members"] = members
            rec["memberIds"] = [str(m.get("id") or m.get("url") or "") for m in members]
            out.setdefault(rec["eventId"], rec)
            continue
        try:
            subs = cluster_items(members, threshold=th, window_hours=wh, weights=weights)
            subs = maybe_merge_clusters(subs, merge_threshold=mth, window_hours=wh, weights=weights)
        except Exception:
            # Never fail a load on revalidation; keep the scrubbed record.
            rec["members"] = members
            rec["memberIds"] = [str(m.get("id") or m.get("url") or "") for m in members]
            out.setdefault(rec["eventId"], rec)
            continue
        # Still one cluster with the same membership -> keep.
        if len(subs) == 1 and len(subs[0].get("members", [])) == len(members):
            sub_ids = {str(m.get("id") or m.get("url") or "") for m in subs[0].get("members", [])}
            orig_ids = {str(m.get("id") or m.get("url") or "") for m in members}
            if sub_ids == orig_ids:
                rec["members"] = [dict(m) for m in subs[0].get("members", [])]
                rec["memberIds"] = [str(m.get("id") or m.get("url") or "") for m in rec["members"]]
                if subs[0].get("firstSeen"):
                    rec["firstSeen"] = subs[0].get("firstSeen")
                if subs[0].get("lastSeen"):
                    rec["lastSeen"] = subs[0].get("lastSeen")
                out.setdefault(rec["eventId"], rec)
                continue
        # Split needed.
        anchor_key = min((_member_sort_key(m) for m in members), default=("", "", ""))
        # Map member id/url -> sub-cluster index for anchor lookup.
        anchor_idx = 0
        for i, s in enumerate(subs):
            keys = {(_member_sort_key(m)) for m in s.get("members", []) if isinstance(m, Mapping)}
            if anchor_key in keys:
                anchor_idx = i
                break
        # Deterministic sub order: anchor first, then by firstSeen/eventId.
        others = [s for i, s in enumerate(subs) if i != anchor_idx]
        others.sort(key=lambda s: (str(s.get("firstSeen") or ""), str(s.get("eventId") or "")))
        ordered_subs = [subs[anchor_idx]] + others
        for si, sub in enumerate(ordered_subs):
            sub_members = [dict(m) for m in sub.get("members", [])]
            sub_members.sort(key=_member_sort_key)
            sub_ids = [str(m.get("id") or m.get("url") or "") for m in sub_members]
            if si == 0:
                new_rec = dict(rec)
                new_rec["eventId"] = rec["eventId"]
                new_rec["members"] = sub_members
                new_rec["memberIds"] = sub_ids
                new_rec["firstSeen"] = sub.get("firstSeen")
                new_rec["lastSeen"] = sub.get("lastSeen")
                if sub_members:
                    new_rec["headline"] = _scrub_str(str(sub_members[0].get("headline") or new_rec.get("headline") or ""))
                # Keep only provenance for remaining members.
                if isinstance(new_rec.get("sources"), list):
                    keep = set(sub_ids)
                    filtered = []
                    for s in new_rec["sources"]:
                        if not isinstance(s, dict):
                            continue
                        # Provenance has no member id; match by url/headline.
                        url = str(s.get("url") or "")
                        mid_match = any(str(m.get("url") or "") == url for m in sub_members) if url else False
                        head = str(s.get("headline") or "")
                        head_match = any(str(m.get("headline") or "") == head for m in sub_members) if head else False
                        if not keep or mid_match or head_match or len(sub_members) == len(members):
                            filtered.append(s)
                    # If filtering emptied but members remain, rebuild minimal provenance.
                    if not filtered and sub_members:
                        filtered = [
                            {"sourceId": str(m.get("sourceId") or ""),
                             "publisher": str(m.get("publisher") or ""),
                             "headline": str(m.get("headline") or ""),
                             "url": str(m.get("url") or ""),
                             "publishedAt": str(m.get("publishedAt") or ""),
                             "rightsMode": str(m.get("rightsMode") or "")}
                            for m in sorted(sub_members, key=lambda m: str(m.get("publishedAt") or ""))
                        ]
                    new_rec["sources"] = filtered
                # Union locations from remaining members (max 3, stable).
                loc_seen: dict[str, dict[str, Any]] = {}
                for m in sub_members:
                    for loc in (m.get("locations") or []):
                        if not isinstance(loc, dict):
                            continue
                        k = json.dumps(loc, sort_keys=True)
                        loc_seen.setdefault(k, dict(loc))
                if loc_seen:
                    new_rec["locations"] = list(loc_seen.values())[:3]
                _drop_brief(new_rec)
                out.setdefault(new_rec["eventId"], new_rec)
            else:
                seed_url = str(sub.get("seedUrl") or (sub_members[0].get("url") if sub_members else "") or "")
                if not seed_url and sub_members:
                    seed_url = str(sub_members[0].get("id") or "")
                new_id = str(sub.get("eventId") or event_id_for_seed(seed_url or new_rec["eventId"]))
                # Avoid collisions deterministically.
                suffix = 1
                base = new_id
                while new_id in out or new_id == rec["eventId"]:
                    # Deterministic fallback: hash base + index.
                    new_id = event_id_for_seed(f"{base}#{suffix}")
                    suffix += 1
                    if suffix > 10:
                        break
                new_rec2: dict[str, Any] = {
                    "eventId": new_id,
                    "members": sub_members,
                    "memberIds": sub_ids,
                    "aliases": [],
                    "version": 1,
                    "status": "updated",
                    "firstSeen": sub.get("firstSeen"),
                    "lastSeen": sub.get("lastSeen"),
                    "headline": _scrub_str(str(sub_members[0].get("headline") or "")) if sub_members else "",
                    "locations": [],
                    "sources": [
                        {"sourceId": str(m.get("sourceId") or ""),
                         "publisher": str(m.get("publisher") or ""),
                         "headline": str(m.get("headline") or ""),
                         "url": str(m.get("url") or ""),
                         "publishedAt": str(m.get("publishedAt") or ""),
                         "rightsMode": str(m.get("rightsMode") or "")}
                        for m in sorted(sub_members, key=lambda m: str(m.get("publishedAt") or ""))
                    ],
                    "lastBriefHash": None,
                    "lastGeneratedAt": None,
                    "lastBrief": None,
                    "lastBriefFingerprint": None,
                    "lastBriefModel": None,
                }
                loc_seen2: dict[str, dict[str, Any]] = {}
                for m in sub_members:
                    for loc in (m.get("locations") or []):
                        if not isinstance(loc, dict):
                            continue
                        k = json.dumps(loc, sort_keys=True)
                        loc_seen2.setdefault(k, dict(loc))
                if loc_seen2:
                    new_rec2["locations"] = list(loc_seen2.values())[:3]
                # Carry section/category/score scaffolding when present on parent.
                for k in ("section", "category"):
                    if rec.get(k) is not None:
                        new_rec2[k] = rec.get(k)
                out.setdefault(new_id, new_rec2)
    return out


def load_state(
    path: str | Path,
    *,
    threshold: float | None = None,
    window_hours: float | None = None,
    weights: Mapping[str, float] | None = None,
    merge_threshold: float | None = None,
) -> dict[str, dict[str, Any]]:
    """Load eventId -> record. Missing/corrupt file -> {} (fresh start).

    Persisted records are scrubbed of U+FFFD and re-validated against the
    current merge rules (see revalidate_persisted_clusters) so pre-#12
    place-only merges heal on load. Threshold overrides let process.py pass
    its configured clustering values; defaults match config.yaml.
    """
    p = Path(path)
    if not p.exists():
        return {}
    try:
        payload = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    raw: dict[str, dict[str, Any]] = {}
    if isinstance(payload, dict) and isinstance(payload.get("clusters"), dict):
        clusters = payload["clusters"]
        raw = {str(k): dict(v) for k, v in clusters.items() if isinstance(v, dict)}
    elif isinstance(payload, dict) and isinstance(payload.get("clusters"), list):
        out: dict[str, dict[str, Any]] = {}
        for rec in payload["clusters"]:
            if isinstance(rec, dict) and rec.get("eventId"):
                out[str(rec["eventId"])] = dict(rec)
        raw = out
    elif isinstance(payload, dict):
        # Bare eventId -> record mapping.
        raw = {str(k): dict(v) for k, v in payload.items() if isinstance(v, dict)}
    else:
        return {}
    try:
        return revalidate_persisted_clusters(
            raw, threshold=threshold, window_hours=window_hours,
            weights=weights, merge_threshold=merge_threshold,
        )
    except Exception:
        # Scrub at minimum; never fail a load.
        for rec in raw.values():
            try:
                _scrub_record(rec)
            except Exception:
                continue
        return raw


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
        # Phase 3 owns brief fields; Phase 2 never clears them, except for
        # the persisted-load repair above (split/FFFD drops) which already
        # cleared prev's brief before this merge. Stored briefs (with their
        # fingerprints) carry forward so the newsroom can reuse them when
        # the source content is unchanged; they expire with the cluster via
        # pruning below.
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
