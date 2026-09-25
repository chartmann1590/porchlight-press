# Run anywhere — Porchlight Press pipeline (Phase 4)

The pipeline is portable by design (`MASTER_PLAN §12 G27`): all logic lives
behind one command with plain file arguments. No `GITHUB_*` environment
variables and no Actions-specific code exist inside `pipeline/`. Workflows
only check out, restore caches, call that command, deploy `public/`, and
commit state. If GitHub ever objects, the same command runs on any machine
with no code changes.

## One command, local machine

Requires Python 3.12+ and the dependencies in `requirements.txt`. For AI
briefs, a llama.cpp `llama-server` must be reachable (otherwise every
cluster ships as a deterministic source card and the run still exits 0).

```bash
python -m pip install -r requirements.txt

# Terminal 1 (optional, for AI briefs): start the model per docs/AI_SETUP.md
llama-server -m ~/.cache/newspaper-models/Qwen3-4B-Q4_K_M.gguf \
  --jinja -c 4096 --host 127.0.0.1 --port 8080

# Terminal 2: the whole ingest -> process -> AI -> images -> publish cycle
python -m pipeline.run --out public/

# Serve the result with any static host:
python -m http.server -d public 8000
# open http://localhost:8000/viewer.html
# open http://localhost:8000/feeds/us/ny/schenectady/latest.json
```

Useful flags (all optional, all plain paths):

```bash
python -m pipeline.run --help
# --out public            static site output (deploy this directory anywhere)
# --state-dir state       runtime state (ETags, clusters, stories; gitignored)
# --sources-dir sources   registry override
# --places FILE           full gazetteer build (CI-time); defaults to trimmed
# --postal-places FILE    full postal build; defaults to trimmed
# --llama-url URL         model server (default http://127.0.0.1:8080)
# --model NAME            force a model label (default: auto by queue size)
# --max-articles N        AI budget cap (default 50)
# --summary-file PATH     append Markdown counters (CI passes $GITHUB_STEP_SUMMARY)
```

Step CLIs (the same pieces `run` orchestrates) also work standalone:

```bash
python -m pipeline.ingest --out state/normalized.json
python -m pipeline.process --in state/normalized.json --out state/clusters.json
python -m pipeline.newsroom --in state/clusters.json --out state/stories.json
python -m pipeline.publish --stories state/stories.json --out public/
python scripts/build_gazetteer.py --mode full --output /tmp/geo-places.json \
  --postal-output /tmp/geo-postal.json
```

## Publish `public/` to any static host

`public/` is a plain static site (JSON + HTML, no server code, no ads):

- `index.json` — available editions + coverage (the app resolves a location
  to a feed path, falling back city → county → metro → state → national)
- `feeds/.../latest.json` (+ `morning`/`afternoon`/`evening` snapshots)
- `feeds/.../breaking.json` — breaking subset for client sync
- `stories/{eventId}.json` — full story + revisions
- `locations/{country}.json` + `{country}-postal.json` — onboarding pickers
- `s/{eventId}.html` — shareable story pages (30-day retention, then `404.html`)
- `viewer.html` — minimal debug viewer (plain JS, no framework)
- `privacy.html` — privacy policy page rendered from `PRIVACY.md` (the Play
  listing links here). Regenerated on every publish; `news-refresh.yml`
  re-checks it before deploying.
- `app-ads.txt` — Authorized Digital Sellers line
  (`google.com, pub-8382831211800454, DIRECT, f08c47fec0942fa0`) for AdMob.
- `.nojekyll` — lets Pages serve underscore paths as-is.

Copy the directory to any static host (Cloudflare Pages, Netlify, nginx,
S3): the app needs only its base URL (`BuildConfig` one-liner). The
Cloudflare Pages mirror stays a ready fallback if GitHub Pages is ever
unavailable.

## $0 and offline notes

- Everything is free public data; no keys for the core path.
- Gazetteer/postal full builds download Census (public domain) + GeoNames
  (CC BY 4.0) at build time and are never committed; see
  `docs/GEO_DATA_SOURCES.md`. Attribution lives in `NOTICE` + the app About
  screen.
- Images come from Wikimedia Commons (CC0/PD/CC BY/CC BY-SA only, captioned
  "File photo: …"); publisher photos are never hotlinked or copied.
- Tests never hit the network: Commons responses and feeds use fixtures
  (`tests/fixtures/`, `python -m pytest tests/ -q -m "not slow"`).
