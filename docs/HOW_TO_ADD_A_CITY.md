# How to add a city — Porchlight Press

Cities are config, not code. The pipeline and the Android app never hardcode
a city; they read the source registry (`sources/`), the metro definitions
(`sources/*/regions.json`), and the gazetteer (`pipeline/geo/*.json`). Adding
a city means adding its sources, placing it on the map, and letting the next
scheduled run publish its edition. No app release is needed for the feed to
exist (the app resolves new feed paths from `index.json`).

## What you get for free once the config lands

- `feeds/{country}/{admin1}/{city}/latest.json` (+ `morning`/`afternoon`/`evening`
  snapshots by local hour) and the city stories backfilled from its
  metro → state → national tiers (`pipeline/publish.py` `_backfill_editions`).
- The city in `locations/{country}.json` (onboarding picker) and its ZIPs in
  `locations/{country}-postal.json` when postal entries exist.
- The metro/state/national editions keep working unchanged; thin city editions
  are filled from wider tiers up to `maxStoriesPerEdition` (default 30).

## Steps

### 1. Add 2–5 live sources for the city

Follow `docs/HOW_TO_ADD_A_SOURCE.md`. Put files under the geographic tree:

```
sources/us/ny/schenectady/city-schenectady-news.json   # example (exists)
sources/us/ny/<city-slug>/<id>.json                    # yours
sources/<cc>/<admin1>/<city-slug>/<id>.json            # non-US: e.g. sources/ca/on/toronto/
```

Each file needs `coverage` with `country`, `admin1` (e.g. `US-NY`), `admin2`
(counties), `cities: ["Schenectady"]`, and `metro` when it belongs to one.
Use `RSS_EXCERPT_ALLOWED` for publishers and municipalities, `PUBLIC_DOMAIN`
for government feeds, `METADATA_ONLY` for GDELT-discovered domains with no
agreement (see the rights table in `HOW_TO_ADD_A_SOURCE.md`). Set
`lastVerified` to the day you fetched the URL.

Verify:

```bash
python scripts/validate_schemas.py
python -m pipeline.sources validate
python -m pipeline.sources check <your-id>
```

`check` must hit a live URL before you commit — do not commit a dead URL
(bot-walled outlets stay out until a live feed or API endpoint is confirmed).

### 2. Put the city on the map

- **Metro:** if the city belongs to a metro, add it to the `cities` (and its
  county to `admin2`) in the metro's `regions.json`
  (e.g. `sources/us/ny/regions.json` → `us-ny-capital-region`). If it starts a
  new metro, add a new region object with `id`, `label`, `country`, `admin1`,
  `admin2`, `cities`, and `timezone`.
- **Non-US `admin1`:** set `coverage.admin1Name` to the real place name
  (e.g. `"admin1": "CA-ON", "admin1Name": "Ontario"`) — GDELT queries use real
  names only.
- **Gazetteer:** the committed trimmed files (`pipeline/geo/places.json`,
  `pipeline/geo/postal.json`) cover the Capital Region test market plus a
  world sample. You do **not** edit them for a normal city: the scheduled
  workflow builds the full gazetteer at CI time from Census + GeoNames
  (`scripts/build_gazetteer.py --mode full`) and consumes it from runner temp.
  Only extend the trimmed files if offline tests need the city
  (`--mode trimmed` rebuilds them; keep them small by policy).

### 3. Open a PR and watch CI

CI runs on every PR: `scripts/validate_schemas.py`, the registry
`validate`, the offline test suite (`pytest -q -m "not slow"`), a live ingest
smoke test (sick feeds are non-fatal), `actionlint`, and `gitleaks`. The
source-validation workflow also live-checks each changed `sources/**/*.json`.

### 4. Confirm the edition after the next scheduled run

`news-refresh.yml` runs every 6 hours and deploys `public/` to Pages. Check:

- `https://chartmann1590.github.io/porchlight-press/feeds/us/ny/<city>/latest.json`
- `index.json` (the edition entry) and `locations/us.json` (the picker entry)

If the city has few of its own stories at first, that is normal: backfill
fills it from the metro/state/national tiers, and coverage deepens as you add
sources. If the town has no local section yet, the app says so and shows the
region/state/country instead (by design).

## Checklist

- [ ] 2–5 live sources under `sources/<cc>/...` with correct `coverage`
- [ ] `rightsMode` follows the rights table; no full-article copying
- [ ] Metro `regions.json` updated (or new region with timezone)
- [ ] `admin1Name` set for non-US admin1 codes
- [ ] `validate_schemas.py` + `sources validate` + `sources check <id>` green
- [ ] PR opened; CI (schemas, tests, actionlint, gitleaks) green
- [ ] Edition + picker entries confirmed after the next 6-hourly run
