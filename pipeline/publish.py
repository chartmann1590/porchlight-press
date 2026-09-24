"""Static JSON API writer (Phase 4).

Outputs (all under ``--out``, default ``public/``)::

    index.json
    feeds/{country}/{admin1}/{place}/{latest|morning|afternoon|evening}.json
    feeds/{country}/{admin1}/state/latest.json
    feeds/{country}/{admin1}/regions/{metro}/latest.json
    feeds/{country}/national/latest.json
    feeds/world/latest.json
    feeds/{...}/breaking.json            (breaking subset, edition-shaped)
    stories/{eventId}.json
    locations/{country}.json             (gazetteer slice for pickers)
    locations/{country}-postal.json      (postal -> place for ZIP onboarding)
    s/{eventId}.html                     (shareable story page, no ads, no JS)
    viewer.html                          (minimal debug viewer, plain JS)
    404.html                             (expiry explanation)

Editions: every run writes ``latest``. When the place's LOCAL hour at
generation falls in 03:00-09:59 / 10:00-15:59 / 16:00-21:59 the same edition
is also snapshotted to ``morning`` / ``afternoon`` / ``evening``. Runs
outside those windows only update ``latest``. Timezones come from the
gazetteer places file + ``sources/*/regions.json`` (fallback America/New_York
for US feeds, else UTC).

Section caps come from ``pipeline/config.yaml`` ``publish`` (defaults below).
Stories are rank-ordered (breaking first, then cluster score, then newest)
and greedily capped per section.

Share pages are regenerated when a story version changes and kept 30 days:
the publisher carries forward previous ``s/*.html`` files younger than 30
days (via ``--prev-share-dir``, wired to ``state/share/`` by run.py) and the
site's ``404.html`` explains expiry afterwards.

Every document is validated against its schema BEFORE any file is written;
an invalid document raises and nothing is deployed (the workflow uploads
``public/`` only on success).

Portable: plain file arguments only, no CI env vars.
"""
from __future__ import annotations

import argparse
import html as html_lib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "schemas"

DEFAULT_CAPS = {
    "maxStoriesPerEdition": 30,
    "maxLocalArticles": 20,
    "maxRegionalArticles": 10,
    "maxStateArticles": 10,
    "maxNationalArticles": 10,
    "maxWorldArticles": 10,
}

SECTION_CAP_KEYS = {
    "local": "maxLocalArticles",
    "regional": "maxRegionalArticles",
    "state": "maxStateArticles",
    "national": "maxNationalArticles",
    "world": "maxWorldArticles",
}

PLAY_URL = (
    "https://play.google.com/store/apps/details"
    "?id=com.charleshartman.porchlightpress"
)


# ---------------------------------------------------------------------------
# Slugs / paths
# ---------------------------------------------------------------------------

def slug(text: str) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", str(text or "").lower()).strip("-")
    base = re.sub(r"-{2,}", "-", base)
    return base or "unknown"


def admin1_slug(admin1: str | None) -> str:
    if not admin1:
        return "unknown"
    code = str(admin1)
    return slug(code.split("-", 1)[1] if "-" in code else code)


def feed_key_for_location(loc: Mapping[str, Any]) -> tuple[str, str | None, str | None, str | None, str]:
    """Return (country_l, admin1_s, city_s|None, metro|None, kind) base key."""
    country = str(loc.get("country") or "US").upper()
    return (country.lower(), admin1_slug(loc.get("admin1")),  # type: ignore[arg-type]
            slug(str(loc.get("city"))) if loc.get("city") else None,
            str(loc.get("metro") or "").lower() or None,
            str(loc.get("admin1") or ""))


def label_for_location(
    loc: Mapping[str, Any],
    places_by_city: dict[str, dict[str, Any]] | None = None,
    metro_labels: dict[str, str] | None = None,
    admin1_names: dict[str, str] | None = None,
) -> str:
    places_by_city = places_by_city or {}
    metro_labels = metro_labels or {}
    admin1_names = admin1_names or {}
    city = str(loc.get("city") or "")
    admin1 = str(loc.get("admin1") or "")
    suffix = admin1.split("-", 1)[1] if "-" in admin1 else admin1
    if city:
        return f"{city}, {suffix}" if suffix else city
    if loc.get("admin2"):
        return str(loc["admin2"])
    if loc.get("metro"):
        metro = str(loc["metro"])
        return metro_labels.get(metro, _humanize_metro(metro))
    if admin1:
        return admin1_names.get(admin1, suffix or admin1)
    country = str(loc.get("country") or "US").upper()
    return {"US": "United States", "GB": "United Kingdom"}.get(country, country)


def _humanize_metro(metro: str) -> str:
    parts = str(metro).split("-", 2)
    tail = parts[2] if len(parts) == 3 else str(metro)
    return " ".join(w.capitalize() for w in tail.replace("_", " ").split()) or metro


# ---------------------------------------------------------------------------
# Timezones / edition slots
# ---------------------------------------------------------------------------

def edition_slot_for_hour(hour: int) -> str | None:
    if 3 <= hour <= 9:
        return "morning"
    if 10 <= hour <= 15:
        return "afternoon"
    if 16 <= hour <= 21:
        return "evening"
    return None


def local_hour(utc_now: datetime, tz_name: str | None) -> int:
    if utc_now.tzinfo is None:
        utc_now = utc_now.replace(tzinfo=timezone.utc)
    if not tz_name:
        return utc_now.hour
    try:
        from zoneinfo import ZoneInfo  # stdlib 3.9+

        return utc_now.astimezone(ZoneInfo(tz_name)).hour
    except Exception:  # noqa: BLE001 - bad tz data -> UTC fallback
        return utc_now.hour


def build_tz_lookups(
    places: list[Mapping[str, Any]],
    regions: list[Mapping[str, Any]],
) -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    """Return (city_tz, metro_tz, admin1_tz) lowercase-keyed lookups."""
    city_tz: dict[str, str] = {}
    admin1_tz: dict[str, str] = {}
    for place in places:
        if not isinstance(place, Mapping):
            continue
        tz = str(place.get("timezone") or "")
        if not tz:
            continue
        if place.get("city"):
            city_tz[str(place["city"]).lower()] = tz
        if place.get("admin1") and place.get("type") in ("admin1", "country", None):
            admin1_tz.setdefault(str(place["admin1"]).lower(), tz)
        elif place.get("admin1"):
            admin1_tz.setdefault(str(place["admin1"]).lower(), tz)
    metro_tz: dict[str, str] = {}
    for region in regions:
        if not isinstance(region, Mapping):
            continue
        tz = str(region.get("timezone") or "")
        rid = str(region.get("id") or "")
        if tz and rid:
            metro_tz[rid.lower()] = tz
    return city_tz, metro_tz, admin1_tz


def timezone_for_location(
    loc: Mapping[str, Any],
    lookups: tuple[dict[str, str], dict[str, str], dict[str, str]] | None = None,
) -> str:
    city_tz, metro_tz, admin1_tz = lookups or ({}, {}, {})
    city = str(loc.get("city") or "").lower()
    if city and city in city_tz:
        return city_tz[city]
    metro = str(loc.get("metro") or "").lower()
    if metro and metro in metro_tz:
        return metro_tz[metro]
    admin1 = str(loc.get("admin1") or "").lower()
    if admin1 and admin1 in admin1_tz:
        return admin1_tz[admin1]
    country = str(loc.get("country") or "US").upper()
    return "America/New_York" if country == "US" else "UTC"


# ---------------------------------------------------------------------------
# Schemas / validation
# ---------------------------------------------------------------------------

def _load_schema(name: str) -> dict[str, Any]:
    import jsonschema

    schema = json.loads((SCHEMAS / name).read_text(encoding="utf-8"))
    jsonschema.validators.validator_for(schema).check_schema(schema)
    return schema


def validate_documents(
    stories: list[dict[str, Any]],
    editions: list[dict[str, Any]],
    index: dict[str, Any],
    locations: dict[str, list[dict[str, Any]]],
    postals: dict[str, list[dict[str, Any]]],
    generated_at: str = "",
) -> None:
    import jsonschema

    story_schema = _load_schema("story.schema.json")
    edition_schema = _load_schema("edition.schema.json")
    index_schema = _load_schema("index.schema.json")
    locations_schema = _load_schema("locations.schema.json")
    postal_schema = _load_schema("postal.schema.json")
    story_v = jsonschema.validators.validator_for(story_schema)(story_schema)
    edition_v = jsonschema.validators.validator_for(edition_schema)(edition_schema)
    index_v = jsonschema.validators.validator_for(index_schema)(index_schema)
    loc_v = jsonschema.validators.validator_for(locations_schema)(locations_schema)
    postal_v = jsonschema.validators.validator_for(postal_schema)(postal_schema)
    errors: list[str] = []
    for story in stories:
        for err in story_v.iter_errors(story):
            errors.append(f"story {story.get('id')}: {err.message}")
    for edition in editions:
        for err in edition_v.iter_errors(edition):
            errors.append(f"edition {edition.get('editionId')}: {err.message}")
    for err in index_v.iter_errors(index):
        errors.append(f"index: {err.message}")
    stamp = generated_at or index.get("generatedAt", "")
    for country, places in locations.items():
        doc = {"apiVersion": 1, "generatedAt": stamp,
               "country": country.upper(), "places": places}
        for err in loc_v.iter_errors(doc):
            errors.append(f"locations/{country}: {err.message}")
    for country, codes in postals.items():
        doc = {"apiVersion": 1, "generatedAt": stamp,
               "country": country.upper(), "postal": codes}
        for err in postal_v.iter_errors(doc):
            errors.append(f"locations/{country}-postal: {err.message}")
    if errors:
        raise ValueError("publish validation failed:\n- " + "\n- ".join(errors[:20]))


# ---------------------------------------------------------------------------
# Share pages / viewer / 404
# ---------------------------------------------------------------------------

def _esc(text: str | None) -> str:
    return html_lib.escape(str(text or ""), quote=True)


def render_share_page(story: Mapping[str, Any], feed_base_url: str) -> str:
    sid = str(story.get("id") or "")
    headline = str(story.get("headline") or "Porchlight Press story")
    dek = str(story.get("dek") or "")
    body = str(story.get("body") or story.get("excerpt") or "")
    ai = bool(story.get("aiGenerated"))
    ai_model = str(story.get("aiModel") or "")
    image = story.get("image") if isinstance(story.get("image"), Mapping) else None
    sources = story.get("sources") if isinstance(story.get("sources"), list) else []
    generated = str(story.get("generatedAt") or "")
    base = feed_base_url.rstrip("/") + "/"
    page_url = base + f"s/{_esc(sid)}.html"
    og_image = f'\n  <meta property="og:image" content="{_esc(str(image.get("url")))}" />' if image and image.get("url") else ""
    disclosure = (
        "<p class=\"disclosure\">AI-written brief from Porchlight Press"
        + (f" ({_esc(ai_model)})" if ai_model else "")
        + ". Reviewed against the linked sources below; see them for the full reporting.</p>"
        if ai
        else "<p class=\"disclosure\">Source card from Porchlight Press — "
        "open the original reporting below to read more.</p>"
    )
    source_items = []
    for src in sources:
        if not isinstance(src, Mapping):
            continue
        url = str(src.get("url") or "")
        if not url.startswith("https://"):
            continue
        pub = _esc(str(src.get("publisher") or ""))
        head = _esc(str(src.get("headline") or url))
        link = _esc(url)
        when = _esc(str(src.get("publishedAt") or ""))
        stamp = f" · {when}" if src.get("publishedAt") else ""
        source_items.append(
            f"<li><a href=\"{link}\">{head}</a>"
            f"<br /><span>{pub}{stamp}</span></li>"
        )
    image_block = ""
    if image and str(image.get("url") or "").startswith("https://"):
        image_block = (
            f"<figure><img src=\"{_esc(str(image.get('url')))}\" alt=\"{_esc(headline)}\" />"
            f"<figcaption>{_esc(str(image.get('attribution') or 'Licensed image'))}</figcaption></figure>"
        )
    description = dek or body[:200]
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="porchlight-generated" content="{_esc(generated)}" />
  <meta name="porchlight-version" content="{_esc(str(story.get('version') or 1))}" />
  <title>{_esc(headline)} — Porchlight Press</title>
  <meta property="og:title" content="{_esc(headline)}" />
  <meta property="og:description" content="{_esc(description)}" />{og_image}
  <meta property="og:url" content="{page_url}" />
  <style>
    body {{ font-family: Georgia, serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.6; color: #111; }}
    .masthead {{ font-family: sans-serif; font-weight: bold; border-bottom: 3px double #111; padding-bottom: 0.5rem; }}
    .disclosure {{ font-family: sans-serif; font-size: 0.85rem; background: #f5f5f5; padding: 0.5rem 0.75rem; border-left: 4px solid #555; }}
    figure {{ margin: 1rem 0; }} img {{ max-width: 100%; height: auto; }} figcaption {{ font-size: 0.8rem; color: #555; }}
    ul {{ padding-left: 1.25rem; }} li {{ margin-bottom: 0.5rem; }} span {{ color: #555; font-size: 0.9rem; }}
    .getapp {{ font-family: sans-serif; margin-top: 2rem; border-top: 1px solid #ccc; padding-top: 1rem; }}
  </style>
</head>
<body>
  <div class="masthead">Porchlight Press</div>
  <h1>{_esc(headline)}</h1>
  {f"<p><em>{_esc(dek)}</em></p>" if dek else ""}
  {image_block}
  {f"<p>{_esc(body)}</p>" if body else ""}
  {disclosure}
  <h2>Sources</h2>
  <ul>{"".join(source_items) if source_items else "<li>Sources unavailable.</li>"}</ul>
  <div class="getapp"><a href="{_esc(PLAY_URL)}">Get the Porchlight Press app</a> for the latest edition.</div>
</body>
</html>
"""


VIEWER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Porchlight Press feed viewer</title>
  <style>
    body { font-family: sans-serif; max-width: 48rem; margin: 2rem auto; padding: 0 1rem; }
    input { width: 100%; padding: 0.5rem; } article { border-bottom: 1px solid #ddd; padding: 0.75rem 0; }
    .meta { color: #555; font-size: 0.85rem; }
  </style>
</head>
<body>
  <h1>Porchlight Press feed viewer</h1>
  <p>Debug viewer (no framework, no ads). Enter any feed path, e.g.
  <code>feeds/us/ny/schenectady/latest.json</code>.</p>
  <input id="feed" value="feeds/us/ny/schenectady/latest.json" />
  <button id="load">Load</button>
  <div id="out"></div>
  <script>
  async function load() {
    const path = document.getElementById('feed').value.trim() || 'feeds/us/ny/schenectady/latest.json';
    const out = document.getElementById('out');
    out.textContent = 'Loading ' + path + ' ...';
    try {
      const res = await fetch(path);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const doc = await res.json();
      out.innerHTML = '';
      const h = document.createElement('h2');
      h.textContent = (doc.editionId || path) + ' (' + (doc.stories || []).length + ' stories)';
      out.appendChild(h);
      for (const s of (doc.stories || [])) {
        const a = document.createElement('article');
        const t = document.createElement('h3'); t.textContent = s.headline || s.id; a.appendChild(t);
        const m = document.createElement('div'); m.className = 'meta';
        m.textContent = (s.aiGenerated ? 'AI brief' : 'Source card') + ' · ' + (s.category || '') + ' · ' + (s.publishedAt || '');
        a.appendChild(m);
        if (s.dek || s.body || s.excerpt) { const p = document.createElement('p'); p.textContent = s.dek || s.body || s.excerpt || ''; a.appendChild(p); }
        out.appendChild(a);
      }
    } catch (e) { out.textContent = 'Failed: ' + e; }
  }
  document.getElementById('load').addEventListener('click', load);
  const q = new URLSearchParams(location.search).get('feed');
  if (q) document.getElementById('feed').value = q;
  load();
  </script>
</body>
</html>
"""

NOT_FOUND_HTML = """<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8" /><title>Story expired — Porchlight Press</title></head>
<body style="font-family: sans-serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem;">
  <h1>This story has expired</h1>
  <p>Share pages are kept for 30 days after publication. Open the latest
  edition in the Porchlight Press app for current coverage.</p>
  <p><a href="viewer.html">Open the feed viewer</a></p>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Feed assembly
# ---------------------------------------------------------------------------

def _story_section(story: Mapping[str, Any], clusters_by_id: Mapping[str, Any]) -> str:
    cluster = clusters_by_id.get(str(story.get("id") or ""))
    if isinstance(cluster, Mapping) and str(cluster.get("section") or ""):
        sec = str(cluster["section"]).lower()
        if sec in SECTION_CAP_KEYS:
            return sec
    # Fallback: infer from the primary location's specificity.
    locs = story.get("locations", []) or []
    loc = locs[0] if locs and isinstance(locs[0], Mapping) else {}
    if loc.get("city"):
        return "local"
    if loc.get("admin2") or loc.get("metro"):
        return "regional"
    if loc.get("admin1"):
        return "state"
    if str(loc.get("country") or "US").upper() == "US":
        return "national"
    return "world"


def order_and_cap(
    stories: list[dict[str, Any]],
    clusters_by_id: Mapping[str, Any],
    caps: Mapping[str, int],
) -> list[dict[str, Any]]:
    def _score(story: Mapping[str, Any]) -> float:
        cluster = clusters_by_id.get(str(story.get("id") or ""))
        if isinstance(cluster, Mapping):
            try:
                return float(cluster.get("score", 0.0) or 0.0)
            except (TypeError, ValueError):
                return 0.0
        return 0.0

    ranked = sorted(
        stories,
        key=lambda s: (
            not bool(s.get("breaking", False)),
            -_score(s),
            str(s.get("publishedAt") or ""),
            str(s.get("id") or ""),
        ),
    )
    max_total = int(caps.get("maxStoriesPerEdition", DEFAULT_CAPS["maxStoriesPerEdition"]))
    per_section: dict[str, int] = {sec: 0 for sec in SECTION_CAP_KEYS}
    kept: list[dict[str, Any]] = []
    for story in ranked:
        if len(kept) >= max_total:
            break
        sec = _story_section(story, clusters_by_id)
        cap = int(caps.get(SECTION_CAP_KEYS[sec], DEFAULT_CAPS[SECTION_CAP_KEYS[sec]]))
        if per_section[sec] >= cap:
            continue
        per_section[sec] += 1
        kept.append(story)
    return kept


def _parse_time(value: Any) -> str:
    return str(value or "")


def build_feeds(
    stories: list[dict[str, Any]],
    clusters_by_id: Mapping[str, Any],
    caps: Mapping[str, int],
    lookups: tuple[dict[str, str], dict[str, str], dict[str, str]],
    places_by_city: dict[str, dict[str, Any]],
    metro_labels: dict[str, str],
    admin1_names: dict[str, str],
    utc_now: datetime,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Group stories into hierarchical feeds; return (feeds, index_entries).

    Each feed: {key, dir, location, timezone, stories}. Hierarchy: a city
    story also appears in its metro (when present), state, and national feeds.
    World stories (non-US / world section) go to the world feed.
    """
    # Bucket by directory.
    buckets: dict[str, dict[str, Any]] = {}

    def _ensure(dirpath: str, location: dict[str, Any]) -> dict[str, Any]:
        if dirpath not in buckets:
            buckets[dirpath] = {
                "dir": dirpath,
                "location": location,
                "timezone": timezone_for_location(location, lookups),
                "story_ids": [],
            }
        return buckets[dirpath]

    story_by_id = {str(s.get("id")): s for s in stories if isinstance(s, dict)}
    for story in stories:
        locs = story.get("locations", []) or []
        loc = dict(locs[0]) if locs and isinstance(locs[0], Mapping) else {"country": "US"}
        country = str(loc.get("country") or "US").upper()
        admin1 = str(loc.get("admin1") or "")
        city = str(loc.get("city") or "")
        metro = str(loc.get("metro") or "").lower()
        sid = str(story.get("id") or "")
        if not sid:
            continue
        if country == "US":
            if city and admin1:
                _ensure(
                    f"feeds/{country.lower()}/{admin1_slug(admin1)}/{slug(city)}/",
                    {"country": country, "admin1": admin1,
                     "admin2": str(loc.get("admin2") or ""),
                     "city": city, "metro": metro or None,
                     "label": label_for_location(loc, places_by_city, metro_labels, admin1_names)},
                )["story_ids"].append(sid)
            if metro and admin1:
                _ensure(
                    f"feeds/{country.lower()}/{admin1_slug(admin1)}/regions/{metro}/",
                    {"country": country, "admin1": admin1, "metro": metro,
                     "label": label_for_location({"country": country, "admin1": admin1, "metro": metro},
                                                 places_by_city, metro_labels, admin1_names)},
                )["story_ids"].append(sid)
            if admin1:
                _ensure(
                    f"feeds/{country.lower()}/{admin1_slug(admin1)}/state/",
                    {"country": country, "admin1": admin1,
                     "label": label_for_location({"country": country, "admin1": admin1},
                                                 places_by_city, metro_labels, admin1_names)},
                )["story_ids"].append(sid)
            _ensure(
                f"feeds/{country.lower()}/national/",
                {"country": country, "label": label_for_location({"country": country}, places_by_city, metro_labels, admin1_names)},
            )["story_ids"].append(sid)
        else:
            _ensure("feeds/world/", {"country": country, "label": label_for_location(loc, places_by_city, metro_labels, admin1_names)})["story_ids"].append(sid)
            # Non-US stories with a city still get a city feed for direct links.
            if city and admin1:
                _ensure(
                    f"feeds/{country.lower()}/{admin1_slug(admin1)}/{slug(city)}/",
                    {"country": country, "admin1": admin1, "city": city,
                     "label": label_for_location(loc, places_by_city, metro_labels, admin1_names)},
                )["story_ids"].append(sid)

    feeds: list[dict[str, Any]] = []
    for dirpath, bucket in buckets.items():
        bucket_stories = [story_by_id[sid] for sid in bucket["story_ids"] if sid in story_by_id]
        capped = order_and_cap(bucket_stories, clusters_by_id, caps)
        bucket["stories"] = capped
        feeds.append(bucket)
    # Deterministic order by directory.
    feeds.sort(key=lambda f: f["dir"])
    return feeds, feeds


def edition_doc(
    feed: Mapping[str, Any],
    kind: str,
    utc_now: datetime,
    edition_prefix: str,
) -> dict[str, Any]:
    stories = list(feed.get("stories", []) or [])
    loc = dict(feed.get("location", {}) or {})
    location = {"country": str(loc.get("country") or "US").upper(), "label": str(loc.get("label") or "World")}
    for key in ("admin1", "admin2", "city", "metro", "timezone"):
        if loc.get(key):
            location[key] = str(loc[key])
    if "timezone" not in location and feed.get("timezone"):
        location["timezone"] = str(feed["timezone"])
    dirpath = str(feed.get("dir", "")).strip("/")
    slug_part = slug(dirpath.replace("/", "-"))[:80]
    edition_id = f"{slug_part}-{kind}-{utc_now.strftime('%Y-%m-%d')}"[:120]
    return {
        "apiVersion": 1,
        "editionId": edition_id,
        "kind": kind,
        "generatedAt": utc_now.isoformat().replace("+00:00", "Z"),
        "location": location,
        "sections": [{"id": "top", "title": "Top Stories",
                      "storyIds": [str(s.get("id")) for s in stories]}] if stories else [],
        "stories": stories,
    }


# ---------------------------------------------------------------------------
# Locations slices
# ---------------------------------------------------------------------------

def build_location_slices(
    places_payload: Mapping[str, Any] | None,
    postal_payload: Mapping[str, Any] | None,
    utc_now: datetime,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, list[dict[str, Any]]]]:
    generated = utc_now.isoformat().replace("+00:00", "Z")
    places = places_payload.get("places", []) if isinstance(places_payload, Mapping) else []
    postal = postal_payload.get("postal", []) if isinstance(postal_payload, Mapping) else []
    by_country: dict[str, list[dict[str, Any]]] = {}
    for place in places or []:
        if not isinstance(place, Mapping):
            continue
        country = str(place.get("country") or "US").lower()
        by_country.setdefault(country, []).append(dict(place))
    postal_by_country: dict[str, list[dict[str, Any]]] = {}
    for entry in postal or []:
        if not isinstance(entry, Mapping):
            continue
        country = str(entry.get("country") or "US").lower()
        postal_by_country.setdefault(country, []).append(dict(entry))
    # Ensure at least the US slice exists (trimmed fallback covers it).
    by_country.setdefault("us", [])
    postal_by_country.setdefault("us", [])
    return by_country, postal_by_country


# ---------------------------------------------------------------------------
# Share carry-forward (30-day retention)
# ---------------------------------------------------------------------------

SHARE_KEEP_DAYS = 30


def _share_generated_at(html_text: str) -> datetime | None:
    match = re.search(r'<meta name="porchlight-generated" content="([^"]+)"', html_text)
    if not match:
        return None
    try:
        return datetime.fromisoformat(match.group(1).replace("Z", "+00:00"))
    except ValueError:
        return None


def carry_forward_share(
    prev_dir: Path | None,
    current_ids: set[str],
    utc_now: datetime,
) -> dict[str, str]:
    """Return {eventId: html} for previous share pages worth keeping.

    Keeps pages younger than 30 days whose stories are no longer in the
    current run (current stories are regenerated fresh by the caller).
    """
    kept: dict[str, str] = {}
    if prev_dir is None or not prev_dir.exists():
        return kept
    for path in sorted(prev_dir.glob("*.html")):
        event_id = path.stem
        if event_id in current_ids or event_id in ("viewer", "404"):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        generated = _share_generated_at(text)
        if generated is None:
            continue
        moment = utc_now if utc_now.tzinfo else utc_now.replace(tzinfo=timezone.utc)
        gen = generated if generated.tzinfo else generated.replace(tzinfo=timezone.utc)
        if (moment - gen).total_seconds() / 86400.0 > SHARE_KEEP_DAYS:
            continue
        kept[event_id] = text
    return kept


# ---------------------------------------------------------------------------
# Stale-brief revalidation (publish-time safety net)
# ---------------------------------------------------------------------------

def _brief_from_ai_story(
    story: Mapping[str, Any], cluster: Mapping[str, Any]
) -> dict[str, Any]:
    """Rebuild a validator-shaped brief from a published AI story.

    Stories carry no people/organizations/sourceIds, so those reconstruct
    as empty/all-members (the entity-span, number, and outcome checks that
    matter here only need headline/dek/body + cluster source text).
    """
    members = [m for m in (cluster.get("members", []) or []) if isinstance(m, Mapping)]
    ids: list[str] = []
    for m in members:
        for key in ("id", "sourceId", "url"):
            val = str(m.get(key) or "").strip()
            if val:
                ids.append(val)
                break
    return {
        "headline": str(story.get("headline") or ""),
        "dek": str(story.get("dek") or ""),
        "body": str(story.get("body") or ""),
        "category": str(story.get("category") or "local"),
        "locations": list(story.get("locations", []) or []),
        "people": [],
        "organizations": [],
        "sourceIds": ids,
        "aiModel": str(story.get("aiModel") or "unknown"),
        "confidence": 0.5,
    }


def revalidate_ai_stories(
    stories: list[dict[str, Any]],
    clusters_by_id: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], int]:
    """Downgrade stale AI briefs that fail the current validator.

    Safety net for briefs generated under an older/weaker validator (e.g.
    the f50f7807554b049d "died" brief): an aiGenerated story whose cluster
    still carries source members is re-checked with validate_brief and
    falls back to a deterministic source card on failure. Stories with no
    linked cluster members are left untouched (nothing to check against).
    Returns (stories, n_downgraded).
    """
    from .ai.providers import SourceCardProvider
    from .ai.validate import validate_brief

    cards = SourceCardProvider()
    out: list[dict[str, Any]] = []
    downgraded = 0
    for story in stories:
        if not isinstance(story, dict) or not story.get("aiGenerated"):
            out.append(story)
            continue
        cluster = clusters_by_id.get(str(story.get("id") or ""))
        members = (cluster.get("members", []) or []) if isinstance(cluster, Mapping) else []
        if not isinstance(cluster, Mapping) or not members:
            out.append(story)
            continue
        result = validate_brief(_brief_from_ai_story(story, cluster), cluster)
        if result.ok:
            out.append(story)
            continue
        out.append(cards.build_card(cluster, None))
        downgraded += 1
    return out, downgraded


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _load_places(path: Path | None, default: Path) -> dict[str, Any]:
    candidate = Path(path) if path else default
    try:
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        return payload if isinstance(payload, Mapping) else {}
    except (OSError, json.JSONDecodeError):
        try:
            return json.loads(default.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}


def _load_regions(sources_dir: Path) -> list[dict[str, Any]]:
    regions: list[dict[str, Any]] = []
    for path in sorted(sources_dir.rglob("regions.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        for region in payload.get("regions", []) or []:
            if isinstance(region, Mapping):
                regions.append(dict(region))
    return regions


def main(argv: list[str] | None = None) -> int:
    from .config import load_config

    parser = argparse.ArgumentParser(prog="pipeline.publish")
    parser.add_argument("--stories", default="state/stories.json")
    parser.add_argument("--clusters", default="state/clusters.json")
    parser.add_argument("--out", default="public")
    parser.add_argument("--sources-dir", default=str(ROOT / "sources"))
    parser.add_argument("--config", default=None)
    parser.add_argument("--places", default=None)
    parser.add_argument("--postal-places", default=None)
    parser.add_argument("--prev-share-dir", default=None)
    parser.add_argument("--generated-at", default=None)
    args = parser.parse_args(argv)

    step = "config"
    try:
        cfg = load_config(args.config)
        publish_cfg = cfg.get("publish", {}) if isinstance(cfg.get("publish"), dict) else {}
        caps = {**DEFAULT_CAPS, **{k: int(v) for k, v in publish_cfg.items() if k in DEFAULT_CAPS}}
        feed_base = str(cfg.get("feedBaseUrl", "https://chartmann1590.github.io/porchlight-press/"))

        step = "load"
        stories_payload = _load_json(Path(args.stories))
        stories = stories_payload.get("stories", []) if isinstance(stories_payload, dict) else []
        stories = [dict(s) for s in stories if isinstance(s, dict)]
        clusters_by_id: dict[str, Any] = {}
        clusters_path = Path(args.clusters)
        if clusters_path.exists():
            clusters_payload = _load_json(clusters_path)
            raw_clusters = clusters_payload.get("clusters", []) if isinstance(clusters_payload, dict) else []
            if isinstance(raw_clusters, dict):
                raw_clusters = list(raw_clusters.values())
            for cluster in raw_clusters or []:
                if isinstance(cluster, Mapping) and cluster.get("eventId"):
                    clusters_by_id[str(cluster["eventId"])] = cluster

        step = "revalidate"
        stories, n_downgraded = revalidate_ai_stories(stories, clusters_by_id)
        if n_downgraded:
            print(f"revalidate: downgraded {n_downgraded} stale AI brief(s) to source cards")

        step = "geo"
        geo_default = ROOT / "pipeline" / "geo" / "places.json"
        postal_default = ROOT / "pipeline" / "geo" / "postal.json"
        places_payload = _load_places(Path(args.places) if args.places else None, geo_default)
        postal_payload = _load_places(Path(args.postal_places) if args.postal_places else None, postal_default)
        places_list = list(places_payload.get("places", []) or [])
        regions = _load_regions(Path(args.sources_dir)) if Path(args.sources_dir).exists() else []
        lookups = build_tz_lookups(places_list, regions)
        places_by_city = {str(p.get("city", "")).lower(): dict(p) for p in places_list if isinstance(p, Mapping) and p.get("city")}
        metro_labels = {str(r.get("id", "")).lower(): str(r.get("label") or r.get("id")) for r in regions}
        admin1_names: dict[str, str] = {}
        for place in places_list:
            if isinstance(place, Mapping) and place.get("admin1") and place.get("admin1Name"):
                admin1_names.setdefault(str(place["admin1"]), str(place["admin1Name"]))

        step = "time"
        if args.generated_at:
            utc_now = datetime.fromisoformat(str(args.generated_at).replace("Z", "+00:00"))
            if utc_now.tzinfo is None:
                utc_now = utc_now.replace(tzinfo=timezone.utc)
        else:
            utc_now = datetime.now(timezone.utc)

        step = "feeds"
        feeds, _ = build_feeds(stories, clusters_by_id, caps, lookups,
                               places_by_city, metro_labels, admin1_names, utc_now)

        step = "editions"
        editions: list[dict[str, Any]] = []
        edition_files: list[tuple[str, dict[str, Any]]] = []  # (rel path, doc)
        index_entries: list[dict[str, Any]] = []
        for feed in feeds:
            latest = edition_doc(feed, "latest", utc_now, "")
            editions.append(latest)
            edition_files.append((feed["dir"] + "latest.json", latest))
            # Breaking subset (edition-shaped, kind latest).
            breaking_stories = [s for s in feed.get("stories", []) if s.get("breaking")]
            if breaking_stories:
                breaking_feed = dict(feed, stories=breaking_stories)
                breaking_doc = edition_doc(breaking_feed, "latest", utc_now, "")
                breaking_doc["editionId"] = latest["editionId"] + "-breaking"
                editions.append(breaking_doc)
                edition_files.append((feed["dir"] + "breaking.json", breaking_doc))
            # Slot snapshot from the feed's LOCAL time.
            slot = edition_slot_for_hour(local_hour(utc_now, feed.get("timezone")))
            slot_doc = None
            if slot:
                slot_doc = edition_doc(feed, slot, utc_now, "")
                editions.append(slot_doc)
                edition_files.append((feed["dir"] + f"{slot}.json", slot_doc))
            # Index the latest + slot (breaking stays out of the index; its
            # kind is not in the index schema enum). The index location
            # drops timezone (not in the index schema; editions keep it).
            for rel, doc in ([(feed["dir"] + "latest.json", latest)]
                             + ([(feed["dir"] + f"{slot}.json", slot_doc)] if slot_doc else [])):
                index_loc = {k: v for k, v in doc["location"].items() if k != "timezone"}
                index_entries.append({
                    "id": slug(feed["dir"] + doc["kind"])[:100],
                    "kind": doc["kind"],
                    "path": rel,
                    "updatedAt": doc["generatedAt"],
                    "storyCount": len(doc.get("stories", [])),
                    "location": index_loc,
                })
        index_entries.sort(key=lambda e: e["path"])
        index_doc = {"apiVersion": 1,
                     "generatedAt": utc_now.isoformat().replace("+00:00", "Z"),
                     "editions": index_entries}

        step = "locations"
        by_country, postal_by_country = build_location_slices(places_payload, postal_payload, utc_now)

        step = "validate"
        validate_documents(stories, editions, index_doc, by_country, postal_by_country,
                           index_doc["generatedAt"])

        step = "write"
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        # Feeds + breaking + slots.
        for rel, doc in edition_files:
            target = out / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        # Stories.
        stories_dir = out / "stories"
        stories_dir.mkdir(parents=True, exist_ok=True)
        for story in stories:
            (stories_dir / f"{story.get('id')}.json").write_text(json.dumps(story, indent=2), encoding="utf-8")
        # Index.
        (out / "index.json").write_text(json.dumps(index_doc, indent=2), encoding="utf-8")
        # Locations.
        loc_dir = out / "locations"
        loc_dir.mkdir(parents=True, exist_ok=True)
        for country, places in by_country.items():
            (loc_dir / f"{country}.json").write_text(json.dumps(
                {"apiVersion": 1, "generatedAt": index_doc["generatedAt"],
                 "country": country.upper(), "places": places}, indent=2), encoding="utf-8")
        for country, codes in postal_by_country.items():
            (loc_dir / f"{country}-postal.json").write_text(json.dumps(
                {"apiVersion": 1, "generatedAt": index_doc["generatedAt"],
                 "country": country.upper(), "postal": codes}, indent=2), encoding="utf-8")
        # Share pages: current + carried-forward (<30d), prune the rest.
        share_dir = out / "s"
        share_dir.mkdir(parents=True, exist_ok=True)
        current_ids = {str(s.get("id")) for s in stories if s.get("id")}
        carried = carry_forward_share(Path(args.prev_share_dir) if args.prev_share_dir else None,
                                      current_ids, utc_now)
        for event_id, carried_html in carried.items():
            (share_dir / f"{event_id}.html").write_text(carried_html, encoding="utf-8")
        for story in stories:
            (share_dir / f"{story.get('id')}.html").write_text(
                render_share_page(story, feed_base), encoding="utf-8")
        # Prune share pages that are neither current nor carried (stale >30d
        # or orphans from a previous layout).
        keep = current_ids | set(carried)
        for path in share_dir.glob("*.html"):
            if path.stem not in keep:
                try:
                    path.unlink()
                except OSError:
                    pass
        (out / "viewer.html").write_text(VIEWER_HTML, encoding="utf-8")
        (out / "404.html").write_text(NOT_FOUND_HTML, encoding="utf-8")

        print(f"published feeds={len(feeds)} files={len(edition_files)} "
              f"stories={len(stories)} share={len(current_ids) + len(carried)} "
              f"locations={len(by_country)} postal={len(postal_by_country)}")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI boundary: one line + non-zero exit
        print(f"publish failed at step {step}: {exc}", file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    import sys

    sys.exit(main())
