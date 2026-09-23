"""Wikimedia Commons provider (Phase 4 images).

Priority chain (plan-literal):
  (1) source-supplied image where ``imageRules.allowReuse`` -> (2) Wikimedia
  Commons -> (3) government/PD image from a PD source -> (4) none (text-only).

Steps (1) and (3) share one check: a cluster member carrying
``imageCandidate`` whose registry source has ``imageRules.allowReuse`` true
AND whose rights mode allows reuse (PUBLIC_DOMAIN / OPEN_LICENSE). No
provider sets ``imageCandidate`` today, so the check is future-proof and
currently falls through to Commons. Publisher images from
RSS_EXCERPT_ALLOWED / METADATA_ONLY / LINK_ONLY sources are NEVER used.

Commons: MediaWiki API search with a descriptive User-Agent; capture url,
creator, license, licenseUrl, sourcePage, attribution from ``extmetadata``.
Accept only CC0 / Public Domain / CC BY / CC BY-SA. Anything with NC/ND
(non-commercial / no-derivatives), GFDL-only, or unknown is rejected.

Relevance guard: a Commons image may only depict a place or entity NAMED in
the story, and it is always captioned "File photo: ...". Never presented as
event photography. If no confident title match, use no image.

Portable: stdlib urllib only, no GITHUB_* env vars. Tests inject ``fetch_fn``
so the default pytest run never hits the network.
"""
from __future__ import annotations

import html as html_lib
import json
import re
import urllib.parse
import urllib.request
from typing import Any, Callable, Mapping

COMMONS_API = "https://commons.wikimedia.org/w/api.php"

APP_UA_TEMPLATE = (
    "PorchlightPress/{version} (+https://github.com/chartmann1590/porchlight-press; {contact})"
)

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")

# Generic words ignored by the relevance guard (keep place/entity tokens).
_STOPWORDS = {"the", "and", "for", "with", "from", "that", "this"}

_LICENSE_URLS = {
    "cc0": "https://creativecommons.org/publicdomain/zero/1.0/",
    "cc by": "https://creativecommons.org/licenses/by/4.0/",
    "cc by-sa": "https://creativecommons.org/licenses/by-sa/4.0/",
    "public domain": "https://en.wikipedia.org/wiki/Public_domain",
}

# Substrings that always reject (non-commercial / no-derivatives).
_REJECT_MARKERS = (
    "non-commercial",
    "noncommercial",
    "-nc",
    " no-deriv",
    "no-deriv",
    "noderiv",
    "no deriv",
    "sampling",
)


def user_agent(contact: str = "me@charleshartman.com", version: str = "0.1") -> str:
    return APP_UA_TEMPLATE.format(version=version, contact=contact)


def strip_html(value: str | None) -> str:
    if not value:
        return ""
    text = _TAG_RE.sub(" ", value)
    text = html_lib.unescape(text)
    return _WS_RE.sub(" ", text).strip()


def is_allowed_license(license_name: str | None) -> bool:
    """Accept only CC0 / Public Domain / CC BY / CC BY-SA.

    Rejects anything with NC/ND markers, GFDL-only, fair-use, or unknown.
    Dual GFDL+CC BY-SA files report a CC short name and are accepted.
    """
    if not license_name:
        return False
    low = license_name.strip().lower()
    for marker in _REJECT_MARKERS:
        if marker in low:
            return False
    # "nd" as a standalone token (e.g. "CC BY-ND 4.0") — avoid matching
    # "...and..." etc. by tokenising.
    tokens = set(re.findall(r"[a-z]+", low))
    if "nc" in tokens or "nd" in tokens:
        return False
    if "cc0" in low or "cc-zero" in low:
        return True
    if "public domain" in low or low in ("pd", "pd-us", "pd-old"):
        return True
    if "cc by-sa" in low or "cc-by-sa" in low:
        return True
    # Bare "CC BY" (with version or trailing space/punctuation).
    if re.search(r"cc[-\s]?by\b", low):
        return True
    return False


def canonical_license_url(license_name: str | None, provided: str | None) -> str | None:
    if provided and provided.startswith(("http://", "https://")):
        return provided
    if not license_name:
        return None
    low = license_name.strip().lower()
    if "cc0" in low:
        return _LICENSE_URLS["cc0"]
    if "cc by-sa" in low or "cc-by-sa" in low:
        return _LICENSE_URLS["cc by-sa"]
    if re.search(r"cc[-\s]?by\b", low):
        return _LICENSE_URLS["cc by"]
    if "public domain" in low:
        return _LICENSE_URLS["public domain"]
    return None


def clean_title(title: str | None) -> str:
    """'File:Schenectady_City_Hall.jpg' -> 'Schenectady City Hall'."""
    if not title:
        return ""
    name = title
    if name.lower().startswith("file:"):
        name = name[5:]
    # Strip the extension.
    name = re.sub(r"\.(jpe?g|png|gif|svg|webp|tiff?)$", "", name, flags=re.I)
    name = name.replace("_", " ").strip()
    return _WS_RE.sub(" ", name)


def format_attribution(title: str, creator: str, license_name: str) -> str:
    """Display string with the required 'File photo:' caption prefix."""
    clean = clean_title(title) or "Untitled"
    if creator:
        return f"File photo: {clean} \u2014 {creator} / Wikimedia Commons ({license_name})"
    return f"File photo: {clean} \u2014 Wikimedia Commons ({license_name})"


def parse_commons_response(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Parse a MediaWiki API response into candidate image dicts.

    Only allowed-license images are returned; the relevance (title) guard is
    applied by the caller, which knows the story query.
    """
    out: list[dict[str, Any]] = []
    query = payload.get("query", {}) if isinstance(payload, Mapping) else {}
    pages = query.get("pages", {}) if isinstance(query, Mapping) else {}
    items = pages.values() if isinstance(pages, dict) else (pages or [])
    for page in items:
        if not isinstance(page, Mapping):
            continue
        if "missing" in page:
            continue
        title = str(page.get("title") or "")
        imageinfo = page.get("imageinfo") or []
        info = imageinfo[0] if imageinfo else {}
        if not isinstance(info, Mapping):
            continue
        url = str(info.get("url") or info.get("thumburl") or "")
        if not url.startswith("https://upload.wikimedia.org/"):
            continue
        meta = info.get("extmetadata") or {}
        if not isinstance(meta, Mapping):
            continue

        def _val(key: str) -> str:
            node = meta.get(key)
            if isinstance(node, Mapping):
                return str(node.get("value") or "")
            return ""

        license_name = strip_html(_val("LicenseShortName")) or strip_html(_val("License"))
        if not is_allowed_license(license_name):
            continue
        creator = strip_html(_val("Artist"))[:200]
        license_url = canonical_license_url(license_name, strip_html(_val("LicenseUrl")))
        source_page = str(info.get("descriptionurl") or "")
        if not source_page.startswith("https://"):
            slug = urllib.parse.quote(title.replace(" ", "_"))
            source_page = f"https://commons.wikimedia.org/wiki/{slug}"
        entry: dict[str, Any] = {
            "url": url,
            "title": title,
            "creator": creator,
            "license": license_name,
            "attribution": format_attribution(title, creator, license_name),
        }
        if license_url:
            entry["licenseUrl"] = license_url
        entry["sourceUrl"] = source_page
        out.append(entry)
    return out


def _default_fetch(url: str, contact: str, timeout: int = 15) -> dict[str, Any]:
    req = urllib.request.Request(url, headers={"User-Agent": user_agent(contact)})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
        return json.loads(resp.read().decode("utf-8", errors="replace"))


FetchFn = Callable[[str], dict[str, Any]]


class CommonsProvider:
    """MediaWiki API search with an injectable fetch for offline tests."""

    def __init__(
        self,
        contact: str = "me@charleshartman.com",
        timeout: int = 15,
        fetch_fn: FetchFn | None = None,
    ) -> None:
        self.contact = contact
        self.timeout = timeout
        self._fetch_fn = fetch_fn or (lambda url: _default_fetch(url, contact, timeout))

    def search_url(self, query: str, limit: int = 3) -> str:
        params = {
            "action": "query",
            "format": "json",
            "generator": "search",
            "gsrsearch": f"{query} filetype:bitmap",
            "gsrnamespace": "6",
            "gsrlimit": str(max(1, min(limit, 10))),
            "prop": "imageinfo",
            "iiprop": "url|extmetadata",
            "iilimit": "1",
        }
        return COMMONS_API + "?" + urllib.parse.urlencode(params)

    def search(self, query: str, limit: int = 3) -> list[dict[str, Any]]:
        """Return allowed-license candidates for *query* (may be empty).

        Transport/parse failures return [] — images are never fatal.
        """
        if not query or not query.strip():
            return []
        try:
            payload = self._fetch_fn(self.search_url(query.strip(), limit))
        except Exception:  # noqa: BLE001 - images degrade to text-only
            return []
        try:
            return parse_commons_response(payload)
        except Exception:  # noqa: BLE001 - malformed fixture/API -> no image
            return []
