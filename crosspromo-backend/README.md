# Hartmann Studios Dynamic Cross-Promotion Platform (Cloudflare Worker)

Production-ready backend for dynamically discovering, ranking, and promoting Hartmann Studios Android applications across the app portfolio.

## Key Features

1. **Zero Hardcoding**:
   - The app catalog is never hardcoded.
   - Dynamically discovers all published Hartmann Studios apps directly from the public Google Play Store developer listing (`https://play.google.com/store/apps/developer?id=Hartmann+Studios`).
   - If a new app is published next month, it is automatically discovered and promoted without needing to update existing apps.
   - If an app is removed or unpublished, it disappears after the cache refresh.

2. **Defensive, Multi-Layer Discovery**:
   - **Strategy 1**: Deep `AF_initDataCallback` extraction from Google Play's server-rendered JSON arrays (extracts canonical package names, high-resolution icons, titles, short descriptions, categories, install counts, ratings).
   - **Strategy 2**: HTML DOM / Regex fallback scanner for maximum resilience if Google updates page markup.
   - **Strategy 3**: KV last-known-good catalog retention.
   - **Safety Gate**: Rejects 0-app discoveries or sudden drops (>75% drop) to ensure existing promotions are never wiped out by upstream format changes.

3. **Smart Ranking & Fairness Engine**:
   - **65% Popularity-Weighted Discovery / 35% Random Exploration Discovery**:
     Higher performing apps get exposure, but smaller/niche apps are never starved.
   - **New App Boost**: Newly discovered packages receive an exploration boost for their first 14 days.
   - **Source App Exclusion**: The host application's package is automatically excluded so users are never promoted the app they are currently using.
   - **Duplicate Elimination**: Responses are strictly deduplicated up to the requested card limit.
   - **Remote Overrides**: Server-side exclusions, manual multipliers, and app-specific pause settings.

4. **Analytics & Attribution**:
   - Ingestion of `promo_impression`, `promo_click`, and `crosspromo_install` events.
   - Aggregates day-bucketed impressions, clicks, and CTR per target app in Cloudflare KV (single versioned stats document, trailing 30-day window).
   - **Self-improving ranking**: recent impressions/clicks feed a Thompson-sampling bandit in the recommendation engine, with the Play Store popularity score as the Bayesian prior.
   - IP rate limiting and bot payload validation.
   - Privacy-first: strictly zero advertising IDs, device IDs, or PII collected.

5. **Cloudflare Serverless Cost-Efficiency**:
   - Runs on Cloudflare Workers + KV.
   - Ultra-low latency edge response (< 15ms globally).
   - Zero ongoing server maintenance or VPS costs.
   - Background Cron Trigger (`0 */6 * * *`) refreshes the catalog every 6 hours.

---

## Operations & Security

- **Admin secret**: configure with `npx wrangler secret put ADMIN_SECRET`. There is no code-level fallback, and obvious placeholder values are refused at request time. Auth accepts only `Authorization: Bearer ...` or `X-Admin-Key` headers (constant-time compared); query-parameter auth was removed because keys in URLs leak into logs.
- **Healthcheck pinging**: set `HEALTHCHECK_PING_URL` (vars or secret) to a cron-monitoring service; the scheduled refresh pings success and signals `/fail` when the refresh is rejected or errors, so a frozen catalog is loud rather than silent.
- **Response contract**: the recommendation response is specified by `schemas/crosspromo-recommendation-response.schema.json` at the repo root; a CI contract test validates live worker output against it. Change the schema and both implementations in the same commit.
- **Local dev state**: `.wrangler/` directories and `scratch_*.js` files are git-ignored scratch space; never commit them.

---

### 1. Health
`GET /api/v1/health`

```json
{
  "status": "ok",
  "catalogApps": 20,
  "lastCatalogRefresh": "2026-09-25T17:12:00.664Z",
  "lastSuccessfulRefresh": "2026-09-25T17:12:00.664Z",
  "cacheStatus": "fresh"
}
```

### 2. Catalog
`GET /api/v1/catalog`
Returns the full normalized catalog of Hartmann Studios applications.

### 3. Recommendations
`GET /api/v1/recommendations?sourcePackage=com.charles.nutrisnap&placement=settings&limit=3`

Parameters:
- `sourcePackage` (string, required): Calling app's package name (automatically excluded from results)
- `placement` (string, optional): UI placement (e.g. `settings`, `home`, `footer`)
- `limit` (int, optional, default: 3, max: 6): Number of unique recommendations to return
- `sessionId` (string, optional): Ephemeral client session ID
- `exclude` (string, optional): Comma-separated package names to exclude

Example Response:
```json
{
  "version": 1,
  "requestId": "b267ba2f-2339-4588-8dae-2f91c9d6f426",
  "generatedAt": "2026-09-25T17:13:05.553Z",
  "expiresAt": "2026-09-25T21:13:05.733Z",
  "apps": [
    {
      "packageName": "com.charles.messenger.v2",
      "name": "TextPilot AI: Smart Messaging",
      "iconUrl": "https://play-lh.googleusercontent.com/...",
      "shortDescription": "A modern SMS app with AI smart replies and built-in web browser.",
      "rating": 4.5,
      "ratingCount": 150,
      "installText": "100+",
      "storeUrl": "https://play.google.com/store/apps/details?id=com.charles.messenger.v2",
      "selectionType": "boost",
      "rankPosition": 1
    }
  ]
}
```

### 4. Events
`POST /api/v1/events`

Payload:
```json
{
  "event": "promo_click",
  "sourcePackage": "com.charleshartman.porchlightpress",
  "targetPackage": "com.auracast.weather",
  "placement": "settings",
  "rankPosition": 1,
  "selectionType": "popular",
  "sessionId": "sess-abc123"
}
```

### 5. Protected Admin Diagnostics
`GET /api/v1/admin/diagnostics`
Header: `Authorization: Bearer <ADMIN_SECRET>`

Returns diagnostics including total impressions, clicks, CTR, rejection count, and runtime configuration.

### 6. Force Catalog Refresh
`POST /api/v1/admin/refresh`
Header: `Authorization: Bearer <ADMIN_SECRET>`

Triggers an immediate live scraping and validation pass.
