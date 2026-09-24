"""Full pipeline entry point (Phase 4): ingest -> process -> AI -> images -> publish.

    python -m pipeline.run --out public/ [--state-dir state/] [...]

Runs on any machine with Python 3.12 and (for AI briefs) the llama.cpp
binary; without a model server every cluster ships as a deterministic source
card and the run still exits 0. Portable: plain file arguments only, no CI
env vars — workflows only check out, restore caches, call this command,
deploy ``public/``, and commit state.

Stages (so the scheduled workflow can pick the AI model BEFORE starting the
model server — no restart is ever needed):

    --stage process ...   ingest -> process -> write the newsroom queue to
                          state/queue.json and the model choice to
                          state/model-choice.txt. Needs no model server.
    --stage finish ...    newsroom (with the chosen --model) -> images ->
                          publish. Needs llama-server for AI briefs.
    --stage all (default) process, then finish. Local end-to-end: reports the
                          chosen model so the operator can serve it.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

ROOT = Path(__file__).resolve().parent.parent


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _stage_process(args: argparse.Namespace, cfg: Mapping[str, Any]) -> int:
    from . import ingest as ingest_mod
    from . import newsroom as newsroom_mod
    from . import process as process_mod

    state_dir = Path(args.state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    normalized_path = state_dir / "normalized.json"
    http_state_path = state_dir / "http-state.json"
    clusters_path = state_dir / "clusters.json"
    summary_args = ["--summary-file", args.summary_file] if args.summary_file else []

    rc = ingest_mod.main([
        "--out", str(normalized_path),
        "--sources-dir", args.sources_dir,
        "--state", str(http_state_path),
        *summary_args,
    ])
    if rc != 0:
        print(f"run: ingest exited {rc}", file=sys.stderr)
        return rc

    process_args = [
        "--in", str(normalized_path),
        "--sources-dir", args.sources_dir,
        "--state", str(clusters_path),
        "--out", str(clusters_path),
    ]
    if args.config:
        process_args += ["--config", args.config]
    if args.places:
        process_args += ["--places", args.places]
    rc = process_mod.main(process_args)
    if rc != 0:
        print(f"run: process exited {rc}", file=sys.stderr)
        return rc

    # Record the newsroom queue + model choice for the next stage (and for
    # the workflow, which downloads and starts exactly this model). Needs no
    # server: --choose-model never contacts one.
    choice_path = Path(args.model_choice_file) if args.model_choice_file else state_dir / "model-choice.txt"
    queue_path = Path(args.queue_file) if args.queue_file else state_dir / "queue.json"
    choose_args = [
        "--choose-model",
        "--in", str(clusters_path),
        "--model-choice-file", str(choice_path),
        "--queue-file", str(queue_path),
        *summary_args,
    ]
    if args.config:
        choose_args += ["--config", args.config]
    if args.model:
        choose_args += ["--model", str(args.model)]
    rc = newsroom_mod.main(choose_args)
    if rc != 0:
        print(f"run: choose-model exited {rc}", file=sys.stderr)
        return rc
    try:
        print(f"run: stage process complete; chosen model {choice_path.read_text(encoding='utf-8').strip()}")
    except OSError:
        pass
    return 0


def _stage_finish(args: argparse.Namespace, cfg: Mapping[str, Any]) -> int:
    from . import newsroom as newsroom_mod
    from . import publish as publish_mod

    ai_cfg = cfg.get("ai", {})
    features = cfg.get("features", {})
    state_dir = Path(args.state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    clusters_path = state_dir / "clusters.json"
    stories_path = state_dir / "stories.json"
    summary_args = ["--summary-file", args.summary_file] if args.summary_file else []

    # The model label matches the server the workflow started (it chose the
    # same file in the process stage). An explicit --model always wins.
    model = args.model
    if model is None:
        choice_path = Path(args.model_choice_file) if args.model_choice_file else state_dir / "model-choice.txt"
        try:
            model = choice_path.read_text(encoding="utf-8").strip()
            if not model:
                model = None
        except OSError:
            model = None

    newsroom_args = [
        "--in", str(clusters_path),
        "--out", str(stories_path),
        "--state", str(clusters_path),
        "--sources-dir", args.sources_dir,
        *summary_args,
    ]
    if args.config:
        newsroom_args += ["--config", args.config]
    llama_url = args.llama_url or ai_cfg.get("llamaUrl")
    if llama_url:
        newsroom_args += ["--llama-url", str(llama_url)]
    if model:
        newsroom_args += ["--model", str(model)]
    max_articles = args.max_articles
    if max_articles is None and features.get("aiMaxArticlesPerRun") is not None:
        max_articles = int(features["aiMaxArticlesPerRun"])
    if max_articles is not None:
        newsroom_args += ["--max-articles", str(max_articles)]
    if args.wall_clock_minutes is not None:
        newsroom_args += ["--wall-clock-minutes", str(args.wall_clock_minutes)]
    if args.enable_factcheck:
        newsroom_args += ["--enable-factcheck"]
    rc = newsroom_mod.main(newsroom_args)
    if rc != 0:
        print(f"run: newsroom exited {rc}", file=sys.stderr)
        return rc

    try:
        from .images import CommonsProvider, enrich_stories
        from .providers import load_sources

        sources = load_sources(Path(args.sources_dir)) if Path(args.sources_dir).exists() else []
        sources_by_id = {str(s.get("id")): dict(s) for s in sources}
        payload = json.loads(stories_path.read_text(encoding="utf-8"))
        stories = payload.get("stories", []) if isinstance(payload, dict) else []
        clusters_payload = json.loads(clusters_path.read_text(encoding="utf-8")) if clusters_path.exists() else {}
        raw_clusters = clusters_payload.get("clusters", []) if isinstance(clusters_payload, dict) else []
        if isinstance(raw_clusters, dict):
            raw_clusters = list(raw_clusters.values())
        clusters_by_id = {str(c.get("eventId")): dict(c) for c in (raw_clusters or []) if isinstance(c, Mapping)}
        provider = CommonsProvider(contact=str(cfg.get("userAgentContact", "me@charleshartman.com")))
        stats = enrich_stories(list(stories), provider,
                               clusters_by_id=clusters_by_id, sources_by_id=sources_by_id)
        stories_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        print(f"images: stories={stats['stories']} with_image={stats['with_image']} "
              f"commons={stats['commons']} supplied={stats['supplied']} none={stats['none']}")
        if args.summary_file:
            with open(args.summary_file, "a", encoding="utf-8") as fh:
                fh.write("## Images\n\n")
                fh.write(f"Stories {stats['stories']}, with image {stats['with_image']} "
                         f"(Commons {stats['commons']}, supplied {stats['supplied']}).\n\n")
    except Exception as exc:  # noqa: BLE001 - images never fail a run
        print(f"run: images degraded to text-only ({exc})", file=sys.stderr)

    # Previous share pages (<30d) persist via state-dir/share/.
    prev_share = state_dir / "share"
    publish_args = [
        "--stories", str(stories_path),
        "--clusters", str(clusters_path),
        "--out", str(out_dir),
        "--sources-dir", args.sources_dir,
    ]
    if args.config:
        publish_args += ["--config", args.config]
    if args.places:
        publish_args += ["--places", args.places]
    if args.postal_places:
        publish_args += ["--postal-places", args.postal_places]
    if prev_share.exists():
        publish_args += ["--prev-share-dir", str(prev_share)]
    if args.generated_at:
        publish_args += ["--generated-at", str(args.generated_at)]
    rc = publish_mod.main(publish_args)
    if rc != 0:
        print(f"run: publish exited {rc} (deploy aborted, run log kept)", file=sys.stderr)
        return rc
    if args.summary_file:
        try:
            index_payload = json.loads((out_dir / "index.json").read_text(encoding="utf-8"))
            editions = index_payload.get("editions", []) or []
            total = sum(int(e.get("storyCount", 0) or 0) for e in editions if isinstance(e, dict))
            with open(args.summary_file, "a", encoding="utf-8") as fh:
                fh.write("## Publish\n\n")
                fh.write(f"Editions {len(editions)}, story entries {total}, "
                         f"share pages {len(list((out_dir / 's').glob('*.html')))}.\n\n")
        except (OSError, ValueError):
            pass

    try:
        public_share = out_dir / "s"
        if public_share.exists():
            prev_share.mkdir(parents=True, exist_ok=True)
            for page in public_share.glob("*.html"):
                shutil.copyfile(page, prev_share / page.name)
            # Drop carried pages that aged out (no longer in public/s/).
            current = {p.name for p in public_share.glob("*.html")}
            for old in prev_share.glob("*.html"):
                if old.name not in current:
                    try:
                        old.unlink()
                    except OSError:
                        pass
    except Exception as exc:  # noqa: BLE001 - persistence is best-effort
        print(f"run: share persistence skipped ({exc})", file=sys.stderr)

    print(f"run complete: out={out_dir} state={state_dir} at={_now_iso()}")
    return 0


def main(argv: list[str] | None = None) -> int:
    from .config import load_config

    parser = argparse.ArgumentParser(prog="pipeline.run")
    parser.add_argument("--out", default="public")
    parser.add_argument("--state-dir", default="state")
    parser.add_argument(
        "--stage",
        choices=["all", "process", "finish"],
        default="all",
        help="all: ingest->publish end to end (default). process: ingest->process "
             "plus queue/model-choice files (no server needed). finish: newsroom->publish.",
    )
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    parser.add_argument("--config", default=None)
    parser.add_argument("--places", default=None)
    parser.add_argument("--postal-places", default=None)
    parser.add_argument("--summary-file", default=None)
    parser.add_argument("--llama-url", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--model-choice-file", default=None)
    parser.add_argument("--queue-file", default=None)
    parser.add_argument("--max-articles", type=int, default=None)
    parser.add_argument("--wall-clock-minutes", type=float, default=None)
    parser.add_argument("--enable-factcheck", action="store_true", default=None)
    parser.add_argument("--generated-at", default=None)
    args = parser.parse_args(argv)

    step = "config"
    try:
        cfg = load_config(args.config)
        if args.stage in ("all", "process"):
            step = "process"
            rc = _stage_process(args, cfg)
            if rc != 0 or args.stage == "process":
                return rc
        step = "finish"
        return _stage_finish(args, cfg)
    except Exception as exc:  # noqa: BLE001 - CLI boundary: one line + non-zero exit
        print(f"run failed at step {step}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
