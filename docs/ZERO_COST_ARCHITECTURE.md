# Zero-cost architecture — Porchlight Press

Everything in this project runs on free tiers with **no billing account
anywhere**. Quotas degrade gracefully; nothing can charge money. This page
names each piece, what it costs ($0), and where the code enforces the limit.

## Summary

| Layer | Service | Cost | What the code does at the limit |
|---|---|---|---|
| News ingest | Public RSS/Atom, GDELT, NWS alerts | $0, no keys | `pipeline/ingest.py` treats feed failures as non-fatal; a sick feed never blocks a run |
| AI briefs | Qwen3-4B (Apache-2.0) via our GitHub Release, Hugging Face fallback; llama.cpp prebuilt CPU | $0, no keys | `ai-newsroom.yml` caches the model, verifies SHA-256 against `pipeline/ai/models.lock`; without a server every cluster ships as a source card, exit 0 |
| Static hosting | GitHub Pages (Actions deploys) | $0 | `news-refresh.yml` publishes `public/`; Cloudflare Pages stays a ready manual mirror |
| CI/CD | GitHub Actions | $0 (public repo) | 6-hourly schedule (`17 4,10,16,22 * * *`), AI wall-clock cap 25 min/run, ~2–2.5 h/day runner load |
| Geo data | US Census (public domain), GeoNames (CC BY 4.0) | $0, build-time only | `scripts/build_gazetteer.py --mode full` runs in CI temp; only trimmed files are committed |
| Images | Wikimedia Commons (CC0/PD/BY/BY-SA) | $0 | `pipeline/images/` relevance-guards every photo; publisher photos are never hotlinked |
| App services | Google AdMob + UMP, ML Kit Translate/Language-ID (on-device), NWS + MET Norway weather (on-device) | $0, no keys | Test ad IDs in debug; consent-gated ads; translation/TTS never leave the phone |
| Crash/stats/config | Firebase Crashlytics, Performance, Analytics (opt-in, default off); Remote Config (client flags only) | $0 (Spark plan, no billing) | `android/app/google-services.json` is gitignored and optional; the app builds and runs without it |
| Secret scanning | Gitleaks action (public repo) | $0, no license needed | `source-validation.yml` `gitleaks` job on every PR/push |

## Rules that keep it $0 (from the plan, enforced in code)

- **No billing account anywhere.** Firebase is Spark-only; `google-services.json`
  is optional and the google-services Gradle plugin applies only when the file
  exists (`android/app/build.gradle.kts`).
- **Pipeline flags live in `pipeline/config.yaml`, never in Remote Config.**
  Remote Config `pp_*` keys are client-only UI flags.
- **Cadence is capped:** news-refresh runs every 6 hours, never faster; the AI
  stage has a 25-minute wall-clock cap and an `aiMaxArticlesPerRun: 50` budget,
  with overflow carried to the next run (4 runs/day).
- **Models are never committed** (`*.gguf` is gitignored). The `model-qwen3`
  Release holds the sharded 4B file (2 × ~1.9 GB) plus the 1.7B fallback plus
  `LICENSE`; `mirror-models.yml` (manual) is idempotent and SHA-verified.
- **Geo dumps are never committed.** Only the trimmed `pipeline/geo/*.json`
  (~20 places, 7 ZIPs) live in the repo; full builds stay in runner temp.
- **No commercial use of GitHub infra:** no ads and no paid tiers in the
  pipeline output; `public/` is plain JSON + HTML with no server code.

## What degrades (instead of failing or charging)

- Model server down → source cards (original headline + link), run still exits 0.
- Gazetteer full build fails → trimmed `pipeline/geo/*.json` fallback, still publishes.
- A feed is sick → counted in the run summary, non-fatal; file a fix, the PR stays green.
- `google-services.json` absent → app builds without Firebase; AdMob uses test IDs in debug.
- `PLAY_SERVICE_ACCOUNT_JSON` absent → `release.yml` still builds and uploads
  the signed AAB as an artifact; the Play step prints setup instructions and succeeds.
- Share pages age out → kept 30 days in `public/s/`, then `404.html` explains expiry.

## Pointers

- Pipeline entry: `python -m pipeline.run --out public/` (`docs/RUN_ANYWHERE.md`).
- Model setup: `docs/AI_SETUP.md` + `pipeline/ai/models.lock` (single source of truth).
- Geo sources/licenses: `docs/GEO_DATA_SOURCES.md`; attribution in `NOTICE`.
- Release/secrets/Play: `README.md` (Release section) + `.github/workflows/release.yml`.
- Troubleshooting (incl. the 60-day schedule re-enable): `docs/TROUBLESHOOTING.md`.
