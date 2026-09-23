# HOW_TO_ADD_A_SOURCE

Sources are config, not code: add a JSON file under `sources/` and open a PR.
CI validates every file against `schemas/source.schema.json`.

## Where the file goes

Geographic, lowercase ISO codes:

```
sources/global/*.json                  # worldwide / discovery (GDELT lives here)
sources/us/national/*.json             # US national
sources/us/ny/state/*.json             # state-level + NWS alerts
sources/us/ny/albany/  schenectady/  saratoga/  rensselaer/  troy/ ...
sources/us/ny/regions.json             # metro definitions (capital-region, ...)
```

Use the country's ISO 3166-1 alpha-2 code (`us`, `ca`, `gb`, ...) and expand
the tree the same way (`sources/ca/on/toronto/`, ...). The Android app and the
pipeline never hardcode a city; they read this tree.

## Schema

```json
{
  "apiVersion": 1,
  "id": "wamc-northeast-report",
  "name": "WAMC Northeast Public Radio",
  "homepage": "https://www.wamc.org/",
  "feedUrl": "https://www.wamc.org/rss.xml",
  "type": "rss",
  "coverage": {
    "country": "US",
    "admin1": "US-NY",
    "admin2": ["Albany County", "Schenectady County"],
    "cities": ["Albany", "Schenectady"],
    "metro": "us-ny-capital-region"
  },
  "rightsMode": "RSS_EXCERPT_ALLOWED",
  "language": "en",
  "enabled": true,
  "priority": 90,
  "imageRules": {"allowReuse": false, "requireAttribution": true},
  "notes": "Why this source matters.",
  "lastVerified": "2026-09-23"
}
```

- `type`: `rss` | `atom` | `gdelt` | `nws-alerts` | `json-api`.
- `feedUrl` (rss/atom) or `apiUrl` (gdelt/nws-alerts/json-api): one is required.
- `priority` 0–100: local public-safety and public broadcasters rank highest.
- `lastVerified`: the date you last fetched the URL successfully. Update it
  when you re-verify.

## Rights modes (behaviour, not just labels)

| Mode | Ingest | Excerpt shown | Fed to AI | Image reuse | Listed |
|---|---|---|---|---|---|
| PUBLIC_DOMAIN | full text | yes | yes | yes | yes |
| OPEN_LICENSE | full text (store `license`) | yes | yes | yes, with attribution | yes |
| RSS_EXCERPT_ALLOWED | feed summary only | yes (≤ 300 chars) | yes | **no** | yes |
| METADATA_ONLY | title/time/url/publisher | no | headline only | no | yes |
| LINK_ONLY | title + url | no | never the basis of a brief | no | yes |
| BLOCKED | dropped at fetch | – | – | – | no |

Rules of thumb:

- Government/public feeds (NWS, USGS): `PUBLIC_DOMAIN`.
- Municipal feeds (e.g. City of Schenectady / Albany News Flash): `RSS_EXCERPT_ALLOWED`
  (NY municipalities retain copyright; excerpt + link). CivicPlus-powered city
  sites list per-category feeds on their `/Rss.aspx` page — use the
  `RSSFeed.aspx?ModID=…&CID=…` URL for the category you want.
- Public broadcasters, commercial outlets with RSS summaries: `RSS_EXCERPT_ALLOWED`.
- GDELT-discovered domains you have no agreement with: `METADATA_ONLY`.
- An outlet with no fetchable feed (bot-walled, e.g. the Daily Gazette and
  CBS6 Albany at seed time — both rate-limit automated fetches; Spotlight News
  and the county sites publish no feed at all): do **not** commit a dead URL.
  Leave it out and note it in your PR; add it once a live feed or API endpoint
  is confirmed.
- `OPEN_LICENSE` requires the `license` string (e.g. `CC BY 4.0`).

The pipeline truncates excerpts per this table automatically; `METADATA_ONLY`
items never carry an excerpt even if the feed offered one.

## Testing your source

```bash
python scripts/validate_schemas.py
python -m pipeline.sources validate
python -m pipeline.sources check <your-id>
```

`check` fetches the feed once and prints the first items — the URL must be
live before you commit. CI runs `validate` plus the test suite on every PR,
then a live ingest smoke test (`pipeline.ingest --summary-file
"$GITHUB_STEP_SUMMARY"`); individual feed failures there are non-fatal by
design, so a sick feed never blocks a PR — file a fix instead.
