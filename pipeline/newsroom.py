"""Newsroom CLI: clusters -> validated AI briefs or source cards.

    python -m pipeline.newsroom [--in FILE] [--out FILE] [--state FILE]
        [--sources-dir DIR] [--config PATH] [--llama-url URL] [--model NAME]
        [--max-articles N] [--wall-clock-minutes N] [--enable-factcheck]
        [--summary-file PATH]

Defaults: --in state/clusters.json, --out state/stories.json,
--state state/clusters.json (updated with lastBriefHash/lastGeneratedAt for
successful briefs so budget-skipped clusters are retried next run).

Budget (MASTER_PLAN section 11, phase-03): AI_MAX_ARTICLES_PER_RUN (default
50) and a wall-clock cap (default 25 min). The queue is processed in rank
order (score desc, local/breaking first via process.py ordering). When more
than ~50 clusters are queued, the 1.7B fallback model is used for the run.
Whatever isn't generated ships as a source card and is retried next run
(unchanged clusters without a brief hash re-enter the queue).

Publish gate: an AI brief publishes only if deterministic validation passes
AND the cluster confidence tier is MEDIUM or better. LOW/UNVERIFIED always
publish as source cards. Any brief that fails validation falls back to the
original headline + link. Never publish unvalidated text.

Portable: plain file arguments only, no CI env vars. A bad model path
(killed llama-server) still yields a complete feed of source cards, exit 0.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from .ai import (
    FALLBACK_MODEL,
    PRIMARY_MODEL,
    CloudflareWorkersAIProvider,
    LocalLlamaProvider,
    SourceCardProvider,
    build_ai_story,
    try_brief_with_retry,
)
from .ai.prompts import build_factcheck_messages
from .ai.validate import source_text_for_cluster
from .config import load_config
from .providers import load_sources

ROOT = Path(__file__).resolve().parent.parent


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _load_clusters(path: Path) -> tuple[list[dict[str, Any]], str]:
    """Return (records, shape) where shape is 'list' or 'dict'."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and isinstance(payload.get("clusters"), list):
        return [dict(c) for c in payload["clusters"] if isinstance(c, dict)], "list"
    if isinstance(payload, dict) and isinstance(payload.get("clusters"), dict):
        clusters = payload["clusters"]
        return [dict(v) for v in clusters.values() if isinstance(v, dict)], "dict"
    if isinstance(payload, list):
        return [dict(c) for c in payload if isinstance(c, dict)], "list"
    raise ValueError(f"{path}: expected {{'clusters': [...]}} or {{'clusters': {{...}}}}")


def _tier_of(cluster: Mapping[str, Any]) -> str:
    tier = str(cluster.get("confidenceTier") or cluster.get("confidence") or "low").lower()
    if tier == "unverified":
        return "unverified"
    return tier if tier in ("high", "medium", "low", "unverified") else "low"


def _ai_usable(cluster: Mapping[str, Any]) -> bool:
    """At least one member may feed AI text and is not LINK_ONLY-only."""
    from .rights import may_feed_ai_text

    for m in (cluster.get("members", []) or []):
        if not isinstance(m, dict):
            continue
        rights = str(m.get("rightsMode") or "")
        if rights == "LINK_ONLY":
            continue
        if may_feed_ai_text(rights):
            return True
    return False


def _queue_for_ai(clusters: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """New/updated MEDIUM+ AI-usable clusters, plus unchanged MEDIUM+ clusters
    that still have no brief hash (budget-skipped last run -> retry)."""
    queue: list[dict[str, Any]] = []
    for c in clusters:
        tier = _tier_of(c)
        if tier not in ("high", "medium"):
            continue
        if not _ai_usable(c):
            continue
        status = str(c.get("status") or "new")
        if status in ("new", "updated"):
            queue.append(c)
        elif status == "unchanged" and not c.get("lastBriefHash"):
            queue.append(c)
    # Rank order: score desc, then firstSeen, then eventId (process.py order).
    queue.sort(
        key=lambda c: (
            -float(c.get("score", 0.0) or 0.0),
            str(c.get("firstSeen") or ""),
            str(c.get("eventId") or ""),
        )
    )
    return queue


def _brief_hash(brief: Mapping[str, Any]) -> str:
    canonical = json.dumps(brief, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pipeline.newsroom")
    parser.add_argument("--in", dest="in_path", default="state/clusters.json")
    parser.add_argument("--out", dest="out_path", default="state/stories.json")
    parser.add_argument("--state", default="state/clusters.json")
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    parser.add_argument("--config", default=None)
    parser.add_argument("--llama-url", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--max-articles", type=int, default=None)
    parser.add_argument("--wall-clock-minutes", type=float, default=None)
    parser.add_argument("--enable-factcheck", action="store_true", default=None)
    parser.add_argument("--summary-file", default=None)
    args = parser.parse_args(argv)

    step = "config"
    try:
        cfg = load_config(args.config)
        features = cfg.get("features", {})
        ai_cfg = cfg.get("ai", {})
        max_articles = (
            args.max_articles
            if args.max_articles is not None
            else int(features.get("aiMaxArticlesPerRun", ai_cfg.get("maxArticlesPerRun", 50)))
        )
        wall_minutes = (
            args.wall_clock_minutes
            if args.wall_clock_minutes is not None
            else float(ai_cfg.get("wallClockMinutes", 25))
        )
        llama_url = args.llama_url or ai_cfg.get("llamaUrl", "http://127.0.0.1:8080")
        overflow_threshold = int(ai_cfg.get("overflowThreshold", 50))
        factcheck_enabled = (
            args.enable_factcheck
            if args.enable_factcheck is not None
            else bool(ai_cfg.get("enableFactCheck", False))
        )

        step = "load"
        in_path = Path(args.in_path)
        if not in_path.exists():
            print(f"no input: {in_path} (run pipeline.process first)", file=sys.stderr)
            return 2
        clusters, shape = _load_clusters(in_path)
        # Deterministic rank order for the published feed.
        clusters.sort(
            key=lambda c: (
                -float(c.get("score", 0.0) or 0.0),
                str(c.get("firstSeen") or ""),
                str(c.get("eventId") or ""),
            )
        )

        step = "sources"
        sources = load_sources(Path(args.sources_dir)) if Path(args.sources_dir).exists() else []
        sources_by_id = {str(s.get("id")): dict(s) for s in sources}

        step = "queue"
        queue = _queue_for_ai(clusters)
        queue_ids = {str(c.get("eventId")) for c in queue}
        # Model choice: >50 queued -> 1.7B fallback for the whole run.
        if args.model:
            model_name = args.model
        elif len(queue) > overflow_threshold:
            model_name = str(ai_cfg.get("fallbackModel", FALLBACK_MODEL))
        else:
            model_name = str(ai_cfg.get("primaryModel", PRIMARY_MODEL))

        step = "providers"
        local = LocalLlamaProvider(base_url=llama_url, model_name=model_name)
        workers = CloudflareWorkersAIProvider()
        cards = SourceCardProvider()

        step = "generate"
        t0 = time.monotonic()
        cap_seconds = max(0.0, wall_minutes * 60.0)
        stories: list[dict[str, Any]] = []
        brief_hashes: dict[str, str] = {}
        brief_times: dict[str, str] = {}
        n_attempted = 0
        n_ai = 0
        n_cards = 0
        n_rejected = 0
        rejection_log: list[str] = []

        def _budget_left() -> bool:
            if n_attempted >= max_articles:
                return False
            if cap_seconds > 0 and (time.monotonic() - t0) >= cap_seconds:
                return False
            return True

        def _factcheck(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> dict | None:
            """Optional second AI pass. Returns {'unsupported': [...]} or None
            when the check itself fails (fail-open: keep the validated brief).

            Uses ``generate_factcheck`` so the model is constrained to the
            factcheck JSON schema (``{"unsupported": [...]}``) rather than the
            brief schema -- the previous call through ``generate_with_messages``
            always failed to parse the factcheck response and the check could
            never reject anything.
            """
            try:
                source_text = source_text_for_cluster(cluster)
                messages = build_factcheck_messages(str(brief.get("body") or ""), source_text)
                unsupported, _raw, err = local.generate_factcheck(messages)
                if err or unsupported is None:
                    return None
                return {"unsupported": unsupported}
            except Exception:  # noqa: BLE001 - optional pass never blocks publish
                return None

        for cluster in clusters:
            eid = str(cluster.get("eventId") or "")
            moment = _now_iso()
            if eid in queue_ids and _budget_left():
                n_attempted += 1
                brief, _result, _raw, err = try_brief_with_retry(local, cluster)
                if brief is None and workers.enabled and _budget_left():
                    brief, _result, _raw2, err2 = try_brief_with_retry(workers, cluster)
                    err = err2 if err2 else err
                    _raw = _raw2
                if brief is not None and factcheck_enabled:
                    fc = _factcheck(brief, cluster)
                    if fc is not None and fc.get("unsupported"):
                        n_rejected += 1
                        rejection_log.append(f"{eid}: factcheck: {fc['unsupported'][:2]}")
                        stories.append(cards.build_card(cluster, sources_by_id))
                        n_cards += 1
                        continue
                if brief is not None:
                    story = build_ai_story(brief, cluster, model_name=model_name, now=datetime.now(timezone.utc))
                    stories.append(story)
                    brief_hashes[eid] = _brief_hash(brief)
                    brief_times[eid] = moment
                    n_ai += 1
                else:
                    n_rejected += 1
                    if err:
                        rejection_log.append(f"{eid}: {str(err)[:220]}")
                    stories.append(cards.build_card(cluster, sources_by_id))
                    n_cards += 1
            else:
                # Not queued (LOW/UNVERIFIED/LINK_ONLY-only), over budget, or
                # out of time: deterministic source card, retried next run
                # when it still lacks a brief hash.
                if eid in queue_ids and not _budget_left():
                    rejection_log.append(f"{eid}: budget-exceeded (card now, retry next run)")
                stories.append(cards.build_card(cluster, sources_by_id))
                n_cards += 1

        step = "write"
        out_path = Path(args.out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps({"generatedAt": _now_iso(), "model": model_name, "stories": stories}, indent=2),
            encoding="utf-8",
        )

        step = "state"
        if brief_hashes:
            state_path = Path(args.state)
            if state_path.exists():
                try:
                    raw_state = json.loads(state_path.read_text(encoding="utf-8"))
                    if isinstance(raw_state.get("clusters"), list):
                        for rec in raw_state["clusters"]:
                            if isinstance(rec, dict) and str(rec.get("eventId") or "") in brief_hashes:
                                eid2 = str(rec["eventId"])
                                rec["lastBriefHash"] = brief_hashes[eid2]
                                rec["lastGeneratedAt"] = brief_times[eid2]
                        state_path.write_text(json.dumps(raw_state, indent=2), encoding="utf-8")
                    elif isinstance(raw_state.get("clusters"), dict):
                        for eid2 in brief_hashes:
                            if eid2 in raw_state["clusters"]:
                                raw_state["clusters"][eid2]["lastBriefHash"] = brief_hashes[eid2]
                                raw_state["clusters"][eid2]["lastGeneratedAt"] = brief_times[eid2]
                        state_path.write_text(json.dumps(raw_state, indent=2), encoding="utf-8")
                except (OSError, ValueError) as exc:
                    print(f"warning: state update skipped: {exc}", file=sys.stderr)
            void_shape = shape  # keep linters quiet about the preserved shape
            del void_shape

        elapsed = time.monotonic() - t0
        print(
            f"clusters={len(clusters)} queued={len(queue)} attempted={n_attempted} "
            f"ai={n_ai} cards={n_cards} rejected={n_rejected} "
            f"model={model_name} elapsed={elapsed:.1f}s"
        )
        for line in rejection_log[:20]:
            print(f"  REJECT {line}")
        if len(rejection_log) > 20:
            print(f"  ... and {len(rejection_log) - 20} more rejections")
        if args.summary_file:
            with open(args.summary_file, "a", encoding="utf-8") as fh:
                fh.write("## AI newsroom\n\n")
                fh.write(
                    f"Clusters {len(clusters)}, queued {len(queue)}, AI briefs {n_ai}, "
                    f"source cards {n_cards}, rejected {n_rejected}, model {model_name}.\n\n"
                )
                for line in rejection_log[:20]:
                    fh.write(f"- REJECT {line}\n")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary: one line + non-zero exit
        print(f"newsroom failed at step {step}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
