"""Process CLI: dedup -> locate -> cluster -> rank -> state.

    python -m pipeline.process [--in FILE] [--sources-dir DIR]
        [--state FILE] [--out FILE] [--config PATH] [--places PATH]

Defaults: --in state/normalized.json, --state/--out state/clusters.json.
Portable: plain file arguments only, no CI env vars. Prints structured
counters: items, duplicates, clusters, new/updated/unchanged.

Exit criterion (Phase 2): turns a fixture batch into clusters with stable
IDs, locations, section, score, confidence tier, and new/updated/unchanged
status.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent


def _load_items(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and isinstance(payload.get("items"), list):
        return [dict(i) for i in payload["items"]]
    if isinstance(payload, list):
        return [dict(i) for i in payload]
    raise ValueError(f"{path}: expected {{'items': [...]}} or [...]")


def _representative_headline(members: list[dict[str, Any]],
                             sources_by_id: dict[str, dict[str, Any]]) -> str:
    """Highest-priority non-LINK_ONLY member headline, tie-break newest."""
    def _key(m: dict[str, Any]) -> tuple[int, str]:
        src = sources_by_id.get(str(m.get("sourceId") or ""), {})
        try:
            prio = int(src.get("priority", 50))
        except (TypeError, ValueError):
            prio = 50
        if str(m.get("rightsMode") or "") == "LINK_ONLY":
            prio -= 100
        return (prio, str(m.get("publishedAt") or ""))

    best = max(members, key=_key) if members else {}
    return str(best.get("headline") or "")


def _coverage_fallback_location(
    members: list[dict[str, Any]],
    sources_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any] | None:
    """Worldwide-safe location fallback from the registry (no US assumption).

    Used only when no member carries a location (the locate.py baseline
    normally guarantees at least one). Returns the first member source's
    registry coverage as a location, or None when no coverage exists — the
    caller then stores no location and maps the cluster to World.
    """
    for m in members:
        src = sources_by_id.get(str(m.get("sourceId") or ""))
        if not isinstance(src, dict):
            continue
        cov = src.get("coverage")
        if not isinstance(cov, dict) or not cov.get("country"):
            continue
        loc: dict[str, Any] = {"country": str(cov["country"])}
        if cov.get("admin1"):
            loc["admin1"] = str(cov["admin1"])
        admin2 = cov.get("admin2")
        if isinstance(admin2, list) and admin2:
            loc["admin2"] = str(admin2[0])
        cities = cov.get("cities")
        if isinstance(cities, list) and cities:
            loc["city"] = str(cities[0])
        if cov.get("metro"):
            loc["metro"] = str(cov["metro"])
        return loc
    return None


def main(argv: list[str] | None = None) -> int:
    from .cluster import cluster_items, maybe_merge_clusters
    from .config import load_config
    from .dedupe import deduplicate
    from .locate import load_places, locate_item
    from .providers import load_sources
    from .rank import confidence_for, independent_publishers, score_cluster
    from .state import load_state, save_state, update_state

    parser = argparse.ArgumentParser(prog="pipeline.process")
    parser.add_argument("--in", dest="in_path", default="state/normalized.json")
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    parser.add_argument("--state", default="state/clusters.json")
    parser.add_argument("--out", default=None,
                        help="Processed output path (defaults to --state).")
    parser.add_argument("--config", default=None)
    parser.add_argument("--places", default=None,
                        help="Gazetteer override (full CI-time build outside the repo).")
    args = parser.parse_args(argv)

    in_path = Path(args.in_path)
    if not in_path.exists():
        print(f"no input: {in_path} (run pipeline.ingest first)", file=sys.stderr)
        return 2

    # CLI-boundary error handling only: pipeline steps never swallow errors
    # internally; any step failure is reported here as one line naming the
    # step, with a non-zero exit.
    step = "config"
    try:
        cfg = load_config(args.config)
        dedupe_cfg = cfg.get("dedupe", {})
        clustering_cfg = cfg.get("clustering", {})
        ranking_cfg = cfg.get("ranking", {})
        state_cfg = cfg.get("state", {})

        step = "load"
        items = _load_items(in_path)
        step = "sources"
        sources = load_sources(Path(args.sources_dir))
        sources_by_id = {str(s.get("id")): dict(s) for s in sources}

        now = datetime.now(timezone.utc)

        # 1. Dedup.
        step = "dedupe"
        unique, dup_groups = deduplicate(
            items,
            threshold=float(dedupe_cfg.get("nearDupThreshold", 85)),
            window_hours=float(dedupe_cfg.get("windowHours", 48)),
        )
        n_duplicates = sum(len(v) for v in dup_groups.values())

        # 2. Locate items (baseline coverage + gazetteer boost).
        step = "locate"
        places = load_places(args.places)
        located: list[dict[str, Any]] = []
        for item in unique:
            src = sources_by_id.get(str(item.get("sourceId") or ""))
            info = locate_item(item, src, places=places)
            enriched = dict(item)
            enriched["locations"] = info["locations"]
            enriched["_section"] = info["section"]
            enriched["_category"] = info["category"]
            located.append(enriched)

        # 3. Cluster (uses per-item locations for the location feature).
        step = "cluster"
        raw_clusters = cluster_items(
            located,
            threshold=float(clustering_cfg.get("similarityThreshold", 0.45)),
            window_hours=float(clustering_cfg.get("windowHours", 72)),
            weights=clustering_cfg.get("weights", {}),
        )
        raw_clusters = maybe_merge_clusters(
            raw_clusters,
            merge_threshold=float(clustering_cfg.get("mergeThreshold", 0.65)),
            window_hours=float(clustering_cfg.get("windowHours", 72)),
            weights=clustering_cfg.get("weights", {}),
        )

        # 4. Aggregate cluster locations/section/category + rank + confidence.
        step = "rank"
        dup_by_kept = {k: len(v) for k, v in dup_groups.items()}
        enriched_clusters: list[dict[str, Any]] = []
        for c in raw_clusters:
            members = [dict(m) for m in c.get("members", [])]
            # Cluster locations: union of member locations (max 3, by
            # frequency). Fallback is the source registry coverage
            # (worldwide-safe); with no coverage either, store no location
            # and map to World rather than assuming a country.
            counted: Counter[str] = Counter()
            by_key: dict[str, dict[str, Any]] = {}
            for m in members:
                for loc in m.get("locations", []) or []:
                    key = json.dumps(loc, sort_keys=True)
                    counted[key] += 1
                    by_key.setdefault(key, dict(loc))
            top_keys = [k for k, _ in counted.most_common(3)]
            if top_keys:
                locations = [by_key[k] for k in top_keys if k in by_key]
            else:
                fallback = _coverage_fallback_location(members, sources_by_id)
                locations = [fallback] if fallback else []
            # Section: most specific member section; no location -> world.
            order = {"local": 0, "regional": 1, "state": 2, "national": 3, "world": 4}
            sections = [str(m.get("_section") or "world") for m in members]
            section = min(sections, key=lambda s: order.get(s, 4)) if sections else "world"
            if not locations:
                section = "world"
            # Category: most common, tie-break seed (first member).
            cats = [str(m.get("_category") or "local") for m in members]
            category = Counter(cats).most_common(1)[0][0] if cats else "local"

            cluster_view: dict[str, Any] = {
                "eventId": c["eventId"], "members": members,
                "memberIds": [str(m.get("id") or m.get("url")) for m in members],
                "aliases": list(c.get("aliases", [])), "firstSeen": c.get("firstSeen"),
                "lastSeen": c.get("lastSeen"), "locations": locations,
                "section": section, "category": category,
            }
            rank_weights = ranking_cfg.get("weights", {})
            n_dup = sum(dup_by_kept.get(str(m.get("id") or m.get("url")), 0) for m in members)
            scored = score_cluster(
                cluster_view, sources_by_id, now=now, weights=rank_weights,
                breaking_min_sources=int(ranking_cfg.get("breakingMinSources", 3)),
                breaking_window_minutes=int(ranking_cfg.get("breakingWindowMinutes", 60)),
                stale_penalty_per_day=float(ranking_cfg.get("stalePenaltyPerDay", 0.05)),
                stale_grace_hours=float(ranking_cfg.get("staleGraceHours", 24)),
                duplicate_penalty=float(ranking_cfg.get("duplicatePenalty", 0.10)),
                n_duplicates=n_dup,
            )
            confidence = confidence_for(members, sources_by_id)
            pubs = independent_publishers(members)
            provenance = [
                {"sourceId": str(m.get("sourceId") or ""),
                 "publisher": str(m.get("publisher") or ""),
                 "headline": str(m.get("headline") or ""),
                 "url": str(m.get("url") or ""),
                 "publishedAt": str(m.get("publishedAt") or ""),
                 "rightsMode": str(m.get("rightsMode") or "")}
                for m in sorted(members, key=lambda m: str(m.get("publishedAt") or ""))
            ]
            cluster_view.update({
                "headline": _representative_headline(members, sources_by_id),
                "score": scored["score"],
                "scoreComponents": scored["components"],
                "breaking": scored["breaking"],
                "confidence": confidence,
                # Schema-compatible lowercase tier (UNVERIFIED -> low; story
                # schema allows only high/medium/low, confidence stays internal).
                "confidenceTier": confidence.lower() if confidence != "UNVERIFIED" else "low",
                "independentSources": len(pubs),
                "sources": provenance,
            })
            # Drop per-item scratch keys from stored members.
            for m in cluster_view["members"]:
                m.pop("_section", None)
                m.pop("_category", None)
            enriched_clusters.append(cluster_view)

        # Highest score first (Phase 3 consumes the queue in rank order).
        enriched_clusters.sort(key=lambda c: (-float(c.get("score", 0.0)),
                                              str(c.get("firstSeen") or ""),
                                              str(c.get("eventId") or "")))

        # 5. State: status/version/aliases/prune, preserving brief hashes.
        step = "state"
        prev = load_state(Path(args.state))
        final = update_state(prev, enriched_clusters, sources_by_id, now=now,
                             prune_days=float(state_cfg.get("pruneDays", 7)))

        step = "write"
        out_path = Path(args.out) if args.out else Path(args.state)
        out_clusters = [final[eid] for eid in
                        sorted(final, key=lambda e: (-float(final[e].get("score", 0.0)),
                                                     str(final[e].get("firstSeen") or ""), e))]
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps({"generatedAt": now.isoformat().replace("+00:00", "Z"),
                                        "clusters": out_clusters}, indent=2), encoding="utf-8")
        if Path(args.state) != out_path:
            save_state(Path(args.state),
                       {eid: final[eid] for eid in final})

        n_new = sum(1 for c in final.values() if c.get("status") == "new")
        n_upd = sum(1 for c in final.values() if c.get("status") == "updated")
        n_unc = sum(1 for c in final.values() if c.get("status") == "unchanged")
        print(f"items={len(items)} unique={len(unique)} duplicates={n_duplicates} "
              f"clusters={len(final)} new={n_new} updated={n_upd} unchanged={n_unc}")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary: one line + non-zero exit
        print(f"process failed at step {step}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
