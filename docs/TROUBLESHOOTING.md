# Troubleshooting — pipeline & scheduled runs (Phase 4)

## Scheduled workflow disabled after 60 days of inactivity

GitHub disables scheduled workflows in public repos after 60 days with no
repository activity. Our `news-refresh.yml` commits `state/` to the
`pipeline-state` branch after every successful run, and branch pushes are
repository activity (they update refs, show in the commit graph, and count
toward the repo's activity), so a healthy 6-hourly schedule keeps itself
enabled indefinitely. This is the mitigation; it has not been observed to
fail, but GitHub does not explicitly promise that bot branch pushes suppress
the auto-disable, so the manual re-enable below is documented either way.

**If `news-refresh` ever shows "Disabled due to inactivity":**

1. GitHub → Actions → **news-refresh** → **Enable workflow**, or via CLI:
   ```bash
   gh api -X PUT repos/chartmann1590/porchlight-press/actions/workflows/news-refresh.yml/enable
   ```
2. Then trigger one run to confirm:
   ```bash
   gh workflow run news-refresh --repo chartmann1590/porchlight-press
   gh run watch --repo chartmann1590/porchlight-press
   ```
3. Check the run summary (sources checked/failed, clusters, briefs,
   deploy URL) and open the feed:
   `https://chartmann1590.github.io/porchlight-press/feeds/us/ny/schenectady/latest.json`

No other manual steps exist. Model mirroring (`mirror-models`), Pages
deployment, gazetteer full builds, and state persistence are all automated
in workflows or scripts.

## Pages shows 404 / feed not updating

1. Confirm Pages is enabled for Actions deploys (one-time, automated):
   ```bash
   gh api repos/chartmann1590/porchlight-press/pages --jq '{build_type, html_url}'
   # expect {"build_type":"workflow", ...}
   ```
   If missing, enable it (no manual console steps):
   ```bash
   gh api -X POST repos/chartmann1590/porchlight-press/pages -f build_type=workflow
   ```
2. Check the latest `news-refresh` run's **Deploy to GitHub Pages** step and
   its `page_url` output (also recorded in the run summary).
3. Feeds are at most ~6 h old by design (6-hourly cadence, MASTER_PLAN §12).
   The app shows "Morning Edition · Updated …", never "Live". Severe-weather
   alerts are unaffected (the app polls NWS directly on-device).

## A run produced only source cards (no AI briefs)

That is the designed fail-safe, not an error: killing the model (bad path)
still yields a complete feed of source cards. Check the run summary's
**AI newsroom** section for `REJECT` reasons, then `docs/AI_SETUP.md`
(model cache, `llama-server --jinja`, `/health`).

## Gazetteer full build failed

The run falls back to the trimmed `pipeline/geo/*.json` automatically and
still publishes. Check the **Build full gazetteer** step log; source URLs
and licenses are listed in `docs/GEO_DATA_SOURCES.md`.

## Invalid feed aborts the deploy (by design)

`pipeline.publish` validates every document against `schemas/` before
writing; an invalid file raises, the workflow fails before
`upload-pages-artifact`, and the previous deployment stays live. Fix the
validation error in the log — never hand-edit `public/`.
