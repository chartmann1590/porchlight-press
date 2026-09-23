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
  normalize.py        Canonicalization + rights filtering
  health.py           Source health checks
  sources.py          Registry validation CLI (python -m pipeline.sources ...)
  ingest.py           Fetch-all CLI (python -m pipeline.ingest)
  process.py          Dedup -> locate -> cluster -> rank -> state (Phase 2)
  newsroom.py         Clusters -> validated AI briefs or source cards (Phase 3)
  config.yaml         Pipeline flags (cheap knobs, no secrets)
sources/              Config-driven source registry (geographic, Phase 1)
schemas/              Versioned JSON Schemas (apiVersion: 1) + taxonomy.json
public/               Generated static feeds (Phase 4 output, deployed to Pages)
docs/                 ARCHITECTURE.md, HOW_TO_ADD_A_SOURCE.md, AI_SETUP.md, ...
scripts/              validate_schemas.py, ai_feasibility/
tests/                pytest: fixtures + pipeline tests
.github/workflows/    CI + scheduled pipeline
```

## Quick start (pipeline)

Requires Python 3.12+ (CI). Local dev works on 3.10+.

```bash
python -m pip install -r requirements.txt
python scripts/validate_schemas.py
python -m pytest tests/ -q
python -m pipeline.sources validate
python -m pipeline.ingest --out state/normalized.json
```

See `docs/HOW_TO_ADD_A_SOURCE.md` for the registry, `docs/ARCHITECTURE.md`
for decisions. The full requirements spec and phase plan live in the local-only
`Plan/` directory (gitignored, never published).
