# Architecture — Porchlight Press

Personalized electronic newspaper: free sources → GitHub Actions (every 6 h) →
normalize → dedupe/cluster → AI newsroom (Qwen3-4B, validated, with source-card
fallback) → static JSON on GitHub Pages → Android app (offline-first).

Developer setup and repo map: `DEVELOPMENT.md`. Source registry guide:
`HOW_TO_ADD_A_SOURCE.md`. New markets: `HOW_TO_ADD_A_CITY.md`. Cost rules:
`ZERO_COST_ARCHITECTURE.md`.

The `Plan/` directory is **local only** (gitignored) and never quoted into
commits or PRs. `Plan/Plan.txt` is the requirements authority;
`Plan/MASTER_PLAN.md` wins on deliberate conflicts.

## Locked decisions (Phase 0)

| Topic | Decision | Why |
|---|---|---|
| Pipeline language | Python 3.12 | Best free libs for feeds/fuzzy matching/llama.cpp bindings |
| Pipeline deps | `feedparser`, `httpx`, `rapidfuzz`, `scikit-learn` (TF-IDF), `jsonschema`, `llama-cpp-python`, `pytest` | All OSS, no keys |
| Android stack | Kotlin, Compose, Material 3, Room, WorkManager, DataStore, Navigation Compose, Retrofit + OkHttp + kotlinx.serialization, Coil 3 | Matches spec; one HTTP stack only |
| DI | Manual `AppContainer` (no Hilt) | Avoid unnecessary dependencies |
| minSdk / target | minSdk 26, compile/target = latest stable | 26 covers ~97% of active devices, gives `java.time`, adaptive icons, notification channels natively |
| Static hosting | GitHub Pages deployed via Actions (primary) | No deploy-count cap for Actions-based deploys; no billing. Cloudflare Pages is an optional mirror |
| Timestamps | ISO 8601 UTC everywhere in JSON/DB; convert at display | Spec: Time |
| HTML | Pipeline reduces all feed HTML to plain text; app never renders remote HTML | Removes the sanitization attack surface |
| JSON compatibility | `apiVersion` field on every document; Android uses `ignoreUnknownKeys = true` | Spec: tolerate unknown fields |
| Identity | Porchlight Press · `com.charleshartman.porchlightpress` · repo `chartmann1590/porchlight-press` (public) · contact `me@charleshartman.com` | See MASTER_PLAN §9 |
| Pipeline cadence | Every 6 h (`17 4,10,16,22 * * *` UTC); AI wall-clock cap 25 min/run | GitHub terms compliance (MASTER_PLAN §12) |
| AI model | Qwen3-4B Q4_K_M primary (benchmark 2026-09-23; always 4B for quality, 4B 5/8 vs 1.7B 1/30) | AI feasibility run, MASTER_PLAN §11 |

## Data flow

```
RSS/Atom + GDELT + NWS alerts
  -> pipeline/providers/*  (fetch)
  -> pipeline/normalize.py (canonicalize, rights filter)
  -> Phase 2: dedup + cluster + locate + rank
  -> Phase 3: AI brief + validate (or source card)
  -> Phase 4: images (Commons CC0/PD/BY/BY-SA, relevance-guarded) + static
     JSON writer -> public/feeds/... + index.json + stories + share pages
  -> one command: python -m pipeline.run --out public/ (see RUN_ANYWHERE.md)
  -> GitHub Pages (6-hourly news-refresh) or any static host
  -> Android: Room cache, WorkManager edition sync
  -> Weather on-device: NWS (US) / MET Norway (worldwide)
```

## Key invariants

- $0 forever: no billing account anywhere; quotas degrade, never charge.
- Rights modes enforced at ingest (`METADATA_ONLY` never yields an excerpt).
- No invented news: validator requires every name/number in AI output to appear in source text.
- Story ID = hash of seed item canonical URL (stable across merges); merges keep older ID + alias.
- `Plan/` stays local; commits end with a co-author trailer; feature branches + PRs.
