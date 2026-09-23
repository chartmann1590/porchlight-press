"""GDELT DOC 2.0 discovery provider.

Builds one query per source from its coverage (cities, counties, state) and
returns title/domain/url/seendate/language/sourcecountry. Items default to
METADATA_ONLY downstream unless the domain matches a registry entry.
Throttled: one request per source with a short pause between pages.
"""
from __future__ import annotations

import time
import urllib.parse
from datetime import datetime, timezone
from typing import Any, Mapping
from urllib.parse import urlparse

from . import RawItem
from .http import get_bytes

API_BASE = "https://api.gdeltproject.org/api/v2/doc/doc"
MAX_RECORDS = 25

# ISO 3166-2 admin1 suffix -> full name for query recall (US seed market;
# extend as the registry grows; unknown codes fall back to the raw suffix).
US_STATE_NAMES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas",
    "CA": "California", "CO": "Colorado", "CT": "Connecticut", "DE": "Delaware",
    "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho",
    "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas",
    "KY": "Kentucky", "LA": "Louisiana", "ME": "Maine", "MD": "Maryland",
    "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota",
    "MS": "Mississippi", "MO": "Missouri", "MT": "Montana", "NE": "Nebraska",
    "NV": "Nevada", "NH": "New Hampshire", "NJ": "New Jersey",
    "NM": "New Mexico", "NY": "New York", "NC": "North Carolina",
    "ND": "North Dakota", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon",
    "PA": "Pennsylvania", "RI": "Rhode Island", "SC": "South Carolina",
    "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas", "UT": "Utah",
    "VT": "Vermont", "VA": "Virginia", "WA": "Washington",
    "WV": "West Virginia", "WI": "Wisconsin", "WY": "Wyoming",
    "DC": "District of Columbia",
}


def build_query(source: Mapping[str, Any]) -> str:
    coverage = source.get("coverage", {})
    terms: list[str] = []
    for city in coverage.get("cities", [])[:6]:
        terms.append(f'"{city}"')
    admin1 = str(coverage.get("admin1", ""))
    if admin1.startswith("US-"):
        code = admin1[3:]
        terms.append(f'"{code}"')
        full = US_STATE_NAMES.get(code)
        if full and full != code:
            terms.append(f'"{full}"')
    for county in coverage.get("admin2", [])[:4]:
        terms.append(f'"{county}"')
    if not terms:
        terms.append(str(source.get("name", "")))
    return " OR ".join(terms)


def _parse_seendate(value: str) -> datetime | None:
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y%m%d%H%M%S"):
        try:
            dt = datetime.strptime(value, fmt)
            return dt.replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


class GdeltProvider:
    def __init__(self, client, max_body_bytes: int):
        self.client = client
        self.max_body_bytes = max_body_bytes

    def fetch(self, source: Mapping[str, Any]) -> list[RawItem]:
        sid = str(source["id"])
        query = build_query(source)
        params = {
            "query": query,
            "mode": "artlist",
            "format": "json",
            "maxrecords": str(MAX_RECORDS),
            "sort": "datedesc",
        }
        url = API_BASE + "?" + urllib.parse.urlencode(params)
        try:
            resp = get_bytes(self.client, url, max_body_bytes=self.max_body_bytes)
            payload = resp.json()
        except Exception as exc:  # noqa: BLE001
            # GDELT asks for at most one request per 5 s; surface its 429 as
            # rate-limited (non-fatal, retried next run) rather than a
            # generic fetch failure.
            from . import provider_error_from_http

            raise provider_error_from_http(
                sid, exc,
                rate_limit_detail="GDELT allows ~1 request per 5 s; back off and retry next run",
            ) from exc
        time.sleep(5)  # throttle: GDELT asks for >= 5 s between requests

        items: list[RawItem] = []
        articles = payload.get("articles", []) if isinstance(payload, dict) else []
        for art in articles:
            link = (art.get("url") or "").strip()
            title = (art.get("title") or "").strip()
            if not link or not title:
                continue
            domain = art.get("domain") or urlparse(link).netloc.lower()
            items.append(
                RawItem(
                    source_id=sid,
                    publisher=domain,
                    title=title,
                    url=link,
                    summary_html=None,  # discovery only; no excerpt rights
                    published_at=_parse_seendate(str(art.get("seendate", ""))),
                    language=(art.get("language") or None),
                    extra={"gdelt_domain": domain, "sourcecountry": art.get("sourcecountry")},
                )
            )
        return items
