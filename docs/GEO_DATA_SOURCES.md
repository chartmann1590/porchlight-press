# Geo data sources — gazetteer + postal (Phase 2)

All $0, no key, build-time only. Nothing here is fetched on-device or billed.

## Sources

| Dataset | URL | License | Use |
|---|---|---|---|
| US Census Gazetteer — places (national, 2024) | https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_place_national.zip | **Public domain** (Title 13 U.S.C.) | Cities, counties, states |
| US Census Gazetteer — ZCTA (national, 2024) | https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_zcta_national.zip | **Public domain** | ZIP → place for onboarding |
| GeoNames `cities15000` | https://download.geonames.org/export/dump/cities15000.zip | **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/) | Worldwide cities |
| GeoNames postal `allCountries` | https://download.geonames.org/export/zip/allCountries.zip | **CC BY 4.0** | Postal codes outside the US |

Attribution: Census (PD, no attribution required) + GeoNames (CC BY 4.0) are
credited in `NOTICE` and (from Phase 5) the app About screen. No coordinates
from these files are ever written onto stories — `pipeline/locate.py` assigns
place names only; `lat`/`lon` appear only when the source feed supplied them.

## Committed (trimmed, small)

Built offline with no network:

```bash
python scripts/build_gazetteer.py --mode trimmed
```

- `pipeline/geo/places.json` — ~20 entries: Capital Region cities/counties/metro,
  NY state, US, plus a world sample (London, Toronto, Paris, …). <100KB.
- `pipeline/geo/postal.json` — 7 Capital Region ZIPs (12207/12208/12210 Albany,
  12307/12308 Schenectady, 12180 Troy, 12866 Saratoga Springs). <50KB.

These cover the test market and offline tests. They are the only geo files in
the repo by policy: never commit large raw dumps.

## Full builds (CI-time only, never committed)

```bash
python scripts/build_gazetteer.py --mode full \
  --output "${RUNNER_TEMP}/geo-places.json" \
  --postal-output "${RUNNER_TEMP}/geo-postal.json" \
  --max-world-cities 2000
```

`--mode full` downloads the dumps above, keeps populous world cities
(pop ≥ 50k, top N), and writes outside the repo. If the result would exceed a
few MB it stays in runner temp / `state/` (gitignored) and is consumed from
there via `pipeline/locate.py`'s `PLACES_PATH` override (env or argument in
Phase 4 publishers). The postal `allCountries` dump is hundreds of MB unpacked;
full postal filtering lives with the Phase 4/5 publishers, not in this repo.

## Policy (from the plan)

- Coordinates are never derived from gazetteer centroids onto stories.
- Postal data is for onboarding pickers only (`locations/{country}-postal.json`
  in Phase 4), never for story coordinates.
- If a source URL moves, update the table above + `scripts/build_gazetteer.py`
  `SOURCES` in the same change.
