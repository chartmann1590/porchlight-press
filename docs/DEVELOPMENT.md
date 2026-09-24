# Development — Porchlight Press

Developer setup and repo map for the pipeline and (from Phase 5) the Android
app. End-user, non-technical docs live elsewhere; see `ARCHITECTURE.md` for
decisions.

## Repo layout

```
android/              Android app (Kotlin/Compose, Phase 5+)
pipeline/             Ingest -> normalize -> cluster -> AI -> publish (Phases 1-4)
  providers/          SourceProvider implementations (rss/atom, gdelt, nws-alerts)
  ai/                 Newsroom: prompts, deterministic validation, providers (llama-server,
                      Workers AI fallback, source cards), models.lock (SHA-256)
  images/             Licensed images: Commons provider (CC0/PD/BY/BY-SA only),
                      place/entity relevance guard, never publisher hotlinks
  normalize.py        Canonicalization + rights filtering
  health.py           Source health checks
  sources.py          Registry validation CLI (python -m pipeline.sources ...)
  ingest.py           Fetch-all CLI (python -m pipeline.ingest)
  process.py          Dedup -> locate -> cluster -> rank -> state (Phase 2)
  newsroom.py         Clusters -> validated AI briefs or source cards (Phase 3)
  publish.py          Static JSON writer: feeds, editions, index, stories,
                      share pages, viewer (Phase 4; validates before writing)
  run.py              One-command portable entry: python -m pipeline.run --out public/
  config.yaml         Pipeline flags (cheap knobs, no secrets)
sources/              Config-driven source registry (geographic, Phase 1)
schemas/              Versioned JSON Schemas (apiVersion: 1) + taxonomy.json
public/               Generated static feeds (Phase 4 output, deployed to Pages)
docs/                 ARCHITECTURE.md, HOW_TO_ADD_A_SOURCE.md, AI_SETUP.md, RUN_ANYWHERE.md, TROUBLESHOOTING.md, ...
scripts/              validate_schemas.py, build_gazetteer.py, ai_feasibility/
tests/                pytest: fixtures + pipeline tests (offline; no network by default)
.github/workflows/    news-refresh (6-hourly publish), source-validation, ai-newsroom, mirror-models
```

## Quick start (pipeline)

Requires Python 3.12+ (CI). Local dev works on 3.10+.

```bash
python -m pip install -r requirements.txt
python scripts/validate_schemas.py
python -m pytest tests/ -q -m "not slow"
python -m pipeline.sources validate
python -m pipeline.ingest --out state/normalized.json
# Full portable run (no GitHub env needed) + any static host for public/:
python -m pipeline.run --out public/
```
Portability: `pipeline/` contains no GitHub-specific logic; see
`docs/RUN_ANYWHERE.md`. Troubleshooting scheduled runs: `docs/TROUBLESHOOTING.md`.

See `docs/HOW_TO_ADD_A_SOURCE.md` for the registry, `docs/ARCHITECTURE.md`
for decisions. The full requirements spec and phase plan live in the local-only
`Plan/` directory (gitignored, never published).
