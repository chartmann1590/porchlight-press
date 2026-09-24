"""Deterministic validation for AI briefs (Phase 3).

Every check is deterministic and offline. Any brief that fails validation
falls back to the original headline + link (source card). Never publish
unvalidated text.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

from .prompts import CATEGORY_IDS

ROOT = Path(__file__).resolve().parent.parent.parent
BRIEF_SCHEMA_PATH = ROOT / "schemas" / "ai-brief.schema.json"

# ---------------------------------------------------------------------------
# Generic text helpers
# ---------------------------------------------------------------------------

_WS_RE = re.compile(r"\s+")
_PUNCT_RE = re.compile(r"[^a-z0-9\s]")
_WORD_RE = re.compile(r"[a-z0-9]+")

STOPWORDS = frozenset({
    "a", "an", "the", "and", "or", "but", "of", "at", "on", "in", "to",
    "for", "with", "by", "from", "up", "out", "as", "is", "are", "was",
    "were", "be", "been", "after", "before", "over", "under", "into",
    "says", "say", "said", "new", "this", "that", "these", "those",
    "it", "its", "they", "them", "their", "his", "her", "our", "your",
    "will", "would", "could", "should", "has", "have", "had", "than",
    "then", "when", "where", "which", "who", "whom", "about", "also",
    "just", "more", "most", "some", "such", "only", "very", "can",
    "according", "including", "reported", "officials", "official",
    "while", "during",
})

# Boilerplate the prompt mandates (uncertainty + disagreement attribution).
# These are not "unsupported background": they carry no factual claim, so
# the coverage guard ignores them.
COVERAGE_SKIP = STOPWORDS | frozenset({
    "sources", "source", "disagree", "disagrees", "disagreed",
    "disagreement", "differ", "differs", "differed", "difference",
    "different", "details", "developing", "information", "unknown",
    "unclear", "ongoing", "number", "numbers",
})

_MULTIWORD_RE = re.compile(r"\b([A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,})+)\b")
_NUMBER_RE = re.compile(r"\$?\d[\d,]*(?:\.\d+)?%?")
# NOTE: trailing (?!\d) (not \b) so ISO datetimes with a time component
# (2026-09-23T09:05:00Z from publishedAt/firstSeen) still parse: \b fails
# between "3" and "T" (both word chars), which hid every source date and
# rejected every brief containing its own publication date.
_ISO_DATE_RE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})(?!\d)")
_MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5,
    "june": 6, "july": 7, "august": 8, "september": 9, "october": 10,
    "november": 11, "december": 12,
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "sept": 9, "oct": 10, "nov": 11, "dec": 12,
}
_MONTH_DAY_RE = re.compile(
    r"\b(January|February|March|April|May|June|July|August|September|October|November|December"
    r"|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+(\d{1,2})(?:\s*,?\s*(\d{4}))?\b",
    re.IGNORECASE,
)
_SLASH_DATE_RE = re.compile(r"\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b")

def _was_reported_on_pattern() -> "re.Pattern[str]":
    months = sorted(_MONTHS, key=len, reverse=True)
    return re.compile(
        r"\bwas\s+reported\s+on\s+(?:" + "|".join(months) + r"|\d)",
        re.IGNORECASE,
    )


_WAS_REPORTED_ON_RE = _was_reported_on_pattern()


_BANNED_BACKGROUND_PHRASES = (
    "casualties",
    "casualty",
    "still under investigation",
    "no casualties",
    "depth under investigation",
    # Self-references to the newsgathering apparatus, never valid prose
    # ("as reported in the headline", "as per the cluster locations/Places"):
    "as reported in the headline",
    "as reported in the excerpt",
    "as noted in the source material",
    "as per the cluster locations",
    "as per the places",
    "as per places",
    "according to the same source",
    "according to the places",
    # Raw location codes/slugs (US-NY, us-ny-capital-region): the locations
    # FIELD carries codes, but body prose must use human-readable names
    # (New York, Capital Region). Normalized "us ny" matches both forms,
    # and no honest brief ever contains it (prose writes "New York").
    "us-ny",
)

AFFECTED_KEYWORDS = frozenset({
    "affected", "homes", "customers", "residents", "displaced", "service",
    "people", "households", "without", "evacuated", "injured", "missing",
    "cases", "deaths", "hospitalized",
})


def _normalize_text(text: str) -> str:
    return _WS_RE.sub(" ", text.lower()).strip()


def _normalize_for_match(text: str) -> str:
    lowered = text.lower()
    cleaned = _PUNCT_RE.sub(" ", lowered)
    return _WS_RE.sub(" ", cleaned).strip()


def _content_words(text: str) -> list[str]:
    tokens = _WORD_RE.findall(text.lower())
    return [t for t in tokens if len(t) >= 3 and t not in STOPWORDS]


def _stem(token: str) -> str:
    """Light stemmer so faithful paraphrases match their sources.

    Applied identically to source and brief text, so "arrested"/"arrest",
    "named"/"name", "videos"/"video", and "preparing"/"prepare" count as the
    same word, while genuinely different words ("menopause" vs
    "perimenopause", "educate" vs "education") stay distinct. Stdlib only,
    deterministic. The critical property is CONSISTENCY: every inflection of
    one lemma must land on one stem (a past bug mapped "named"->"nam" but
    "name"->"name", failing valid paraphrases).
    """
    # Sequential (no early return): plural strip, then trailing-e strip, so
    # "places"->"place"->"plac" meets brief-side "place"->"plac".
    t = token
    if len(t) > 5 and t.endswith("ies"):
        return t[:-3] + "y"
    verb_stripped = False
    if len(t) > 5 and t.endswith("ing"):
        t = t[:-3]
        verb_stripped = True
    elif len(t) > 4 and t.endswith("ed"):
        t = t[:-2]
        verb_stripped = True
    if not verb_stripped:
        # Skipped after a verb strip: the -s in "clos" (from "closed")
        # is stem, not a plural (brief-side "close"->"clos" must meet it).
        if len(t) > 5 and t.endswith(("ses", "xes", "zes", "ches", "shes")):
            t = t[:-2]  # classes->class, boxes->box
        elif len(t) > 3 and t.endswith("s") and not t.endswith("ss"):
            t = t[:-1]  # videos->video, places->place; press/class intact
    if len(t) > 3 and t.endswith("e"):
        t = t[:-1]  # place->plac, name->nam, prepare->prepar
    return t


def _coverage_words(text: str) -> list[str]:
    """Stemmed content words for the background-coverage guard.

    Boilerplate skipped; stopwords ignored. Stemming lets a faithful
    paraphrase ("the arrest occurred") match its source ("police arrested")
    instead of being flagged as invented background.
    """
    tokens = _WORD_RE.findall(text.lower())
    return [_stem(t) for t in tokens if len(t) >= 3 and t not in COVERAGE_SKIP]


_WEEKDAYS = frozenset({
    "monday", "tuesday", "wednesday", "thursday", "friday",
    "saturday", "sunday", "mon", "tue", "tues", "wed", "thu",
    "thur", "thurs", "fri", "sat", "sun",
})

_MONTH_BY_NUM = {
    1: ("january", "jan"), 2: ("february", "feb"), 3: ("march", "mar"),
    4: ("april", "apr"), 5: ("may",), 6: ("june", "jun"),
    7: ("july", "jul"), 8: ("august", "aug"), 9: ("september", "sep", "sept"),
    10: ("october", "oct"), 11: ("november", "nov"), 12: ("december", "dec"),
}


def _month_words(month: int) -> tuple[str, ...]:
    """Full + abbreviated month names for a month number (coverage vocab)."""
    return _MONTH_BY_NUM.get(month, ())


def _words_for_verbatim(text: str) -> list[str]:
    return _WORD_RE.findall(text.lower())


# Abbreviations whose periods never end a sentence (U.S., a.m./p.m.,
# e.g./i.e., Dr./Mr./Mrs./Ms./St./Jr./Sr., Jan.-Dec., single initials).
_ABBREV_RE = re.compile(
    r"\b(?:[A-Za-z]\.){2,}"  # U.S., U.S.A., p.m., a.m.
    r"|\b(?:e\.g|i\.e)\."  # e.g., i.e. (also matched above; kept explicit)
    r"|\b(?:Dr|Mr|Mrs|Ms|St|Jr|Sr|vs|etc|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\."
    r"|\b[A-Z]\."  # single initials (J., M.)
)
_MASK = "\x00"


def _split_sentences(text: str) -> list[str]:
    """Split on .!? without breaking after abbreviations.

    A naive ``re.split(r"[.!?]+")`` fragments "U.S.", "Dr. Smith",
    "9 a.m.", etc. into <5-word pieces that slip past the coverage
    guard's short-sentence skip. Masking abbreviation periods first keeps
    each real sentence whole so it is actually checked.
    """
    masked = _ABBREV_RE.sub(lambda m: m.group(0).replace(".", _MASK), text)
    return [p.replace(_MASK, ".").strip() for p in re.split(r"[.!?]+", masked) if p.strip()]


@dataclass
class ValidationResult:
    ok: bool
    reasons: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# JSON parsing (parser tests: valid, truncated, extra fields, wrong types)
# ---------------------------------------------------------------------------

def parse_brief_json(raw: str) -> tuple[dict[str, Any] | None, str | None]:
    """Parse model output into a dict.

    Returns (brief, None) on success, (None, reason) on failure.
    Truncated JSON, wrong types (non-object), and empty output all fail
    here; extra fields and wrong field types parse fine and fail later
    in schema validation.
    """
    if raw is None or not str(raw).strip():
        return None, "empty model output"
    text = str(raw).strip()
    # Tolerate markdown fences from non-grammar fallbacks (grammar output
    # should be pure JSON, but robustness costs nothing).
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```\s*$", "", text)
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        return None, f"invalid JSON (truncated or malformed): {exc.msg} at col {exc.colno}"
    if not isinstance(data, dict):
        return None, f"wrong top-level type: expected object, got {type(data).__name__}"
    return data, None


def parse_factcheck_json(raw: str) -> tuple[list[str] | None, str | None]:
    """Parse model output for the factcheck shape {"unsupported": [...]}.

    Returns (unsupported_list, None) on success, (None, reason) on failure.
    The response must be a JSON object whose "unsupported" member is a list
    of strings. One or more non-empty strings means the brief is not fully
    supported and should be rejected.
    """
    data, err = parse_brief_json(raw)
    if err is not None:
        return None, err
    unsupported = data.get("unsupported")
    if not isinstance(unsupported, list):
        return None, "unsupported must be an array"
    for i, item in enumerate(unsupported):
        if not isinstance(item, str):
            return None, f"unsupported[{i}] must be a string, got {type(item).__name__}"
    return unsupported, None


def _brief_schema_validator():
    import jsonschema

    schema = json.loads(BRIEF_SCHEMA_PATH.read_text(encoding="utf-8"))
    cls = jsonschema.validators.validator_for(schema)
    cls.check_schema(schema)
    return cls(schema)


# ---------------------------------------------------------------------------
# Cluster helpers
# ---------------------------------------------------------------------------

def _members(cluster: Mapping[str, Any]) -> list[dict[str, Any]]:
    members = cluster.get("members", []) or []
    return [dict(m) for m in members if isinstance(m, Mapping)]


def _member_id_set(cluster: Mapping[str, Any]) -> tuple[set[str], dict[str, dict[str, Any]]]:
    """All acceptable sourceId spellings + lookup to the member."""
    ids: set[str] = set()
    lookup: dict[str, dict[str, Any]] = {}
    for m in _members(cluster):
        for key in (m.get("id"), m.get("sourceId"), m.get("url")):
            if key:
                s = str(key).strip()
                if s:
                    ids.add(s)
                    lookup.setdefault(s, m)
                    lookup.setdefault(s.lower(), m)
    return ids, lookup


def source_text_for_cluster(
    cluster: Mapping[str, Any], *, include_timestamps: bool = True
) -> str:
    parts: list[str] = []
    for m in _members(cluster):
        parts.append(str(m.get("publisher") or ""))
        parts.append(str(m.get("headline") or ""))
        parts.append(str(m.get("excerpt") or ""))
        if include_timestamps and m.get("publishedAt"):
            parts.append(str(m.get("publishedAt")))
    return " ".join(p for p in parts if p)


def _source_text_headline_excerpt(cluster: Mapping[str, Any]) -> str:
    parts: list[str] = []
    for m in _members(cluster):
        parts.append(str(m.get("publisher") or ""))
        parts.append(str(m.get("headline") or ""))
        parts.append(str(m.get("excerpt") or ""))
    return " ".join(p for p in parts if p)


@lru_cache(maxsize=1)
def _gazetteer_lookups() -> tuple[dict[str, list[str]], dict[str, list[str]], dict[str, list[str]]]:
    """Cached gazetteer expansions: admin1 code -> names, metro slug -> names,
    country code -> names. Missing file -> empty maps (exact location strings
    still count; only alias expansion is lost)."""
    admin1: dict[str, list[str]] = {}
    metro: dict[str, list[str]] = {}
    country: dict[str, list[str]] = {}
    try:
        payload = json.loads((ROOT / "pipeline" / "geo" / "places.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return admin1, metro, country
    places = payload.get("places", []) if isinstance(payload, dict) else []
    for p in places:
        if not isinstance(p, dict):
            continue
        name = str(p.get("name") or "")
        aliases = [str(a) for a in (p.get("aliases") or []) if a]
        # Strict: admin1 expansions come ONLY from the admin1 entry itself
        # (US-NY -> New York + NY/New York State/Empire State), never from
        # admin2/city rows that merely share the code. Same for metros: only
        # the metro row (Capital Region + Capital District), never every
        # place inside that metro.
        if p.get("type") == "admin1" and p.get("admin1"):
            code = str(p["admin1"])
            entry = [str(p.get("admin1Name") or name)] + aliases
            if name and name not in entry:
                entry.append(name)
            admin1.setdefault(code, [])
            for n in entry:
                if n and n not in admin1[code]:
                    admin1[code].append(n)
        if p.get("type") == "metro" and p.get("metro"):
            slug = str(p["metro"])
            entry = ([name] + aliases) if name else aliases
            metro.setdefault(slug, [])
            for n in entry:
                if n and n not in metro[slug]:
                    metro[slug].append(n)
        if p.get("country") and p.get("type") == "country":
            code = str(p["country"]).upper()
            entry = ([name] + aliases) if name else aliases
            country.setdefault(code, [])
            for n in entry:
                if n and n not in country[code]:
                    country[code].append(n)
    return admin1, metro, country


@lru_cache(maxsize=1)
def _gazetteer_geo() -> tuple[dict[str, set[tuple[str, frozenset[str]]]], dict[str, str]]:
    """City -> {(admin2, admin1-names)} and state-name -> admin1-key maps.

    From gazetteer city rows (name + aliases): Troy -> {(Rensselaer County,
    {New York, NY, ...})}. Same-named cities in other states each contribute
    an entry, so Springfield -> {(Sangamon County, {Illinois,...}), (Hampden
    County, {Massachusetts,...})}. State names from admin1 rows. Missing file ->
    empty maps (containment claims then need source-stated pairings).
    All keys/values normalized for match.
    """
    city_areas: dict[str, set[tuple[str, frozenset[str]]]] = {}
    state_names: dict[str, str] = {}
    try:
        payload = json.loads((ROOT / "pipeline" / "geo" / "places.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return city_areas, state_names
    places = payload.get("places", []) if isinstance(payload, dict) else []
    for p in places:
        if not isinstance(p, dict):
            continue
        if p.get("type") == "admin1" and p.get("admin1"):
            code = str(p["admin1"])
            names = [str(p.get("admin1Name") or ""), str(p.get("name") or "")]
            names += [str(a) for a in (p.get("aliases") or []) if a]
            for n in names:
                norm = _normalize_for_match(n)
                if norm:
                    state_names.setdefault(norm, code)
        if p.get("type") == "city":
            admin2 = _normalize_for_match(str(p.get("admin2") or ""))
            a1names = [_normalize_for_match(str(p.get("admin1Name") or ""))]
            admin1_map, _, _ = _gazetteer_lookups()
            code = str(p.get("admin1") or "")
            if code in admin1_map:
                a1names += [_normalize_for_match(a) for a in admin1_map[code]]
            area = (admin2, frozenset(n for n in a1names if n))
            names = [str(p.get("name") or "")]
            if p.get("city"):
                names.append(str(p["city"]))
            names += [str(a) for a in (p.get("aliases") or []) if a]
            for n in names:
                norm = _normalize_for_match(n)
                if norm:
                    city_areas.setdefault(norm, set()).add(area)
    return city_areas, state_names


def _cluster_location_names(
    cluster: Mapping[str, Any], *, include_metro: bool = True
) -> set[str]:
    """Normalized place names from the cluster's own resolved locations.

    Covers city, county/admin2, state/admin1 full names and their common
    forms (gazetteer aliases: NY / New York State / Empire State, Capital
    Region / Capital District, United States, ...). Only names tied to THIS
    cluster count -- an invented county/state still fails. Strict everywhere
    else: people, orgs, numbers, quotes, and background filler are unchanged.

    ``include_metro=False`` drops the metro slug and its aliases: the
    event-location guard needs city/county/state/country only, since
    asserting an event "occurred in" the broad metro is exactly the vague
    attribution it rejects.
    """
    admin1_map, metro_map, country_map = _gazetteer_lookups()
    allowed: set[str] = set()
    locs = cluster.get("locations", []) or []
    for loc in locs:
        if not isinstance(loc, Mapping):
            continue
        for key in ("city", "admin2", "metro", "admin1"):
            if key == "metro" and not include_metro:
                continue
            raw = str(loc.get(key) or "").strip()
            if not raw:
                continue
            norm = _normalize_for_match(raw)
            if norm:
                allowed.add(norm)
            if key == "admin1" and raw in admin1_map:
                for alias in admin1_map[raw]:
                    n = _normalize_for_match(alias)
                    if n:
                        allowed.add(n)
            if key == "metro" and raw in metro_map:
                for alias in metro_map[raw]:
                    n = _normalize_for_match(alias)
                    if n:
                        allowed.add(n)
        country_code = str(loc.get("country") or "").strip().upper()
        if country_code in country_map:
            for alias in country_map[country_code]:
                n = _normalize_for_match(alias)
                if n:
                    allowed.add(n)
    return allowed


# ---------------------------------------------------------------------------
# Individual checks
# ---------------------------------------------------------------------------

def _check_schema(brief: Mapping[str, Any]) -> list[str]:
    reasons: list[str] = []
    # Pipeline fills these when the model omits them; validate the rest.
    filled = dict(brief)
    if not filled.get("aiModel"):
        filled["aiModel"] = "unknown"
    if not filled.get("generatedAt"):
        filled["generatedAt"] = "2026-01-01T00:00:00Z"
    try:
        validator = _brief_schema_validator()
    except Exception as exc:  # noqa: BLE001 - schema load failure is a rejection
        return [f"schema unavailable: {exc}"]
    for err in validator.iter_errors(filled):
        path = "/".join(map(str, err.path)) or "(root)"
        reasons.append(f"schema: {path}: {err.message}")
    # Category must be in the single taxonomy even if schema drifts.
    cat = str(brief.get("category") or "")
    if cat and cat not in CATEGORY_IDS:
        reasons.append(f"category not in taxonomy: {cat!r}")
    return reasons


def _check_source_ids(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> list[str]:
    reasons: list[str] = []
    members = _members(cluster)
    if not members:
        return ["cluster has no members"]
    for m in members:
        if not str(m.get("url") or "").strip():
            reasons.append(f"source missing URL: {m.get('id') or m.get('sourceId') or '?'}")
    cited = brief.get("sourceIds", [])
    if not isinstance(cited, list) or not cited:
        return reasons + ["sourceIds must be a non-empty list"]
    id_set, lookup = _member_id_set(cluster)
    id_set_lower = {s.lower() for s in id_set}
    for sid in cited:
        s = str(sid)
        if s not in id_set and s.lower() not in id_set_lower:
            reasons.append(f"unknown sourceId: {s!r}")
            continue
        member = lookup.get(s) or lookup.get(s.lower())
        if member is not None and not str(member.get("url") or "").strip():
            reasons.append(f"cited source has no URL: {s!r}")
    return reasons


def _check_entities(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> list[str]:
    """Every multi-word capitalized span in body+dek and every people/org
    entry must appear in the source text -- OR be a place name from the
    cluster's own resolved locations (city/county/state full names and
    common forms: New York for US-NY, Albany County, ...). Headline Title
    Case is skipped (run on body + dek only) per the benchmark fix."""
    reasons: list[str] = []
    source_norm = _normalize_for_match(_source_text_headline_excerpt(cluster))
    location_names = _cluster_location_names(cluster)
    body = str(brief.get("body") or "")
    dek = str(brief.get("dek") or "")
    text = f"{body} {dek}"
    spans = set(_MULTIWORD_RE.findall(text))
    for span in spans:
        # Sentence-initial articles ("The Central Avenue ...") are not names:
        # strip one leading The/A/An and re-check the remainder.
        core = re.sub(r"^(?:The|A|An)\s+", "", span.strip())
        if not core or " " not in core:
            continue  # single word left -- single words are not entity-checked
        norm = _normalize_for_match(core)
        if not norm:
            continue
        if norm in source_norm:
            continue
        if norm in location_names:
            continue
        # Weekday-aware: the model often writes "<Place> on <Weekday>" as
        # "<Place> <Weekday>" ("Central Avenue Tuesday"). Bare weekdays are
        # dates, not names (the date check ignores them too), so a span
        # whose non-weekday remainder is grounded is not a fake place.
        # An invented place still fails ("Los Angeles Tuesday" -> "los
        # angeles" is in neither sources nor locations).
        words = norm.split()
        remainder = " ".join(w for w in words if w not in _WEEKDAYS)
        if remainder and remainder != norm:
            if remainder in source_norm or remainder in location_names:
                continue
            if " " not in remainder:
                continue  # only a weekday + one other word: not an entity
        reasons.append(f"unsupported name/span not in sources: {span!r}")
    for field_name in ("people", "organizations"):
        entries = brief.get(field_name, []) or []
        if not isinstance(entries, list):
            continue
        for entry in entries:
            norm = _normalize_for_match(str(entry))
            if not norm:
                continue
            if norm not in source_norm:
                reasons.append(f"unsupported {field_name[:-1]} not in sources: {entry!r}")
    return reasons


def _extract_dates(text: str) -> tuple[list[tuple[int | None, int, int]], list[str]]:
    """Parse dates; return ([(year|None, month, day)], [matched substrings])."""
    found: list[tuple[int | None, int, int]] = []
    spans: list[str] = []
    for m in _ISO_DATE_RE.finditer(text):
        try:
            y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        except ValueError:
            continue
        if 1 <= mo <= 12 and 1 <= d <= 31:
            found.append((y, mo, d))
            spans.append(m.group(0))
    for m in _MONTH_DAY_RE.finditer(text):
        try:
            mo = _MONTHS[m.group(1).lower()]
            d = int(m.group(2))
            y = int(m.group(3)) if m.group(3) else None
            if y is not None and y < 100:
                y += 2000 if y < 50 else 1900
        except (ValueError, KeyError):
            continue
        if 1 <= d <= 31:
            found.append((y, mo, d))
            spans.append(m.group(0))
    for m in _SLASH_DATE_RE.finditer(text):
        try:
            a, b = int(m.group(1)), int(m.group(2))
            y = m.group(3)
        except ValueError:
            continue
        # Assume M/D (US market); skip bare times like 5/2 vote? A vote
        # "5-2" uses a dash, not a slash, so slash dates are real dates.
        if 1 <= a <= 12 and 1 <= b <= 31:
            year: int | None = None
            if y:
                year = int(y)
                if year < 100:
                    year += 2000 if year < 50 else 1900
            found.append((year, a, b))
            spans.append(m.group(0))
    return found, spans


def _norm_num(token: str) -> str:
    return token.replace("$", "").replace(",", "").replace("%", "").strip()


def _extract_numbers(text: str) -> set[str]:
    out: set[str] = set()
    for m in _NUMBER_RE.finditer(text):
        tok = m.group(0)
        if not re.search(r"\d", tok):
            continue
        norm = _norm_num(tok)
        if norm:
            out.add(norm)
            # Float-canonical form so "98.40" matches "98.4".
            try:
                out.add(str(float(norm)))
            except ValueError:
                pass
    return out


def _strip_dates(text: str) -> str:
    cleaned = _ISO_DATE_RE.sub(" ", text)
    cleaned = _MONTH_DAY_RE.sub(" ", cleaned)
    # Slash dates: only strip when they look like dates (M/D with valid ranges).
    def _slash_repl(m: re.Match[str]) -> str:
        try:
            a, b = int(m.group(1)), int(m.group(2))
        except ValueError:
            return m.group(0)
        if 1 <= a <= 12 and 1 <= b <= 31:
            return " "
        return m.group(0)

    return _SLASH_DATE_RE.sub(_slash_repl, cleaned)


def _check_numbers_and_dates(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    reasons: list[str] = []
    # Source text includes publishedAt values (benchmark fix), normalized to
    # (year, month, day) so any format counts (2026-09-23 == September 23,
    # 2026 == 9/23/2026). Cluster firstSeen/lastSeen double as the run date:
    # a brief dated the day it ran must not fail when every source carries
    # that same timestamp.
    source_full = source_text_for_cluster(cluster, include_timestamps=True)
    for key in ("firstSeen", "lastSeen"):
        stamp = str(cluster.get(key) or "").strip()
        if stamp:
            source_full += " " + stamp
    source_dates, _ = _extract_dates(source_full)
    source_nodate = _strip_dates(source_full)
    source_nums = _extract_numbers(source_nodate)

    output_text = " ".join(
        str(brief.get(k) or "") for k in ("headline", "dek", "body")
    )
    output_dates, _ = _extract_dates(output_text)
    for year, month, day in output_dates:
        if year is not None:
            if (year, month, day) not in source_dates:
                reasons.append(f"date not in sources: {year:04d}-{month:02d}-{day:02d}")
        else:
            if not any(mo == month and d == day for _, mo, d in source_dates):
                # Also allow month-day appearing as words in source text
                # (e.g. excerpt says "Tuesday" won't match; that's fine,
                # bare weekdays are not parsed as dates here).
                reasons.append(f"date not in sources: month {month} day {day}")
    output_nodate = _strip_dates(output_text)
    output_nums = _extract_numbers(output_nodate)
    # Float set for tolerant comparison.
    source_floats: set[float] = set()
    for n in source_nums:
        try:
            source_floats.add(float(n))
        except ValueError:
            pass
    for n in sorted(output_nums):
        if n in source_nums:
            continue
        try:
            if float(n) in source_floats:
                continue
        except ValueError:
            pass
        reasons.append(f"number not in sources: {n!r}")
    return reasons


def _check_background_coverage(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    reasons: list[str] = []
    source_text = _source_text_headline_excerpt(cluster)
    source_norm = _normalize_for_match(source_text)
    brief_text = str(brief.get("body") or "") + " " + str(brief.get("dek") or "")
    brief_norm = _normalize_for_match(brief_text)
    # Meta-date filler ("The event was reported on September 23, 2026"):
    # passive self-reference to the newsgathering act, never a fact from the
    # sources. Active publisher attribution ("WNYT reported ... on ...") is
    # unaffected -- only "was reported on <month|digit>" triggers.
    if _WAS_REPORTED_ON_RE.search(brief_text):
        reasons.append("unsupported background phrase not in sources: 'was reported on <date>'")
    for phrase in _BANNED_BACKGROUND_PHRASES:
        # Compare normalized to normalized: entries with punctuation
        # ("us-ny") must match "us ny" in the brief text.
        nphrase = _normalize_for_match(phrase)
        if nphrase and nphrase in brief_norm:
            if nphrase not in source_norm:
                reasons.append(f"unsupported background phrase not in sources: {phrase!r}")
    source_vocab = set(_coverage_words(source_text))
    # The cluster's resolved place names count as covered: a faithful
    # paraphrase ("the arrest occurred in Rensselaer County, New York")
    # reuses the settled geography even when one short excerpt never spells
    # it out. Component words only (never whole inventable claims).
    for name in _cluster_location_names(cluster):
        for w in _WORD_RE.findall(name):
            if len(w) >= 3 and w not in COVERAGE_SKIP:
                source_vocab.add(_stem(w))
    # Source dates count as covered in any format: "September 23, 2026" in
    # the brief matches the 2026-09-23T publishedAt stamps. Month names for
    # every sourced date join the vocab (years/days are already there when
    # spelled numerically; weekday names stay uncovered by design).
    dated_text = source_text_for_cluster(cluster, include_timestamps=True)
    for key in ("firstSeen", "lastSeen"):
        stamp = str(cluster.get(key) or "").strip()
        if stamp:
            dated_text += " " + stamp
    for _y, mo, _d in _extract_dates(dated_text)[0]:
        for cand in _month_words(mo):
            if len(cand) >= 3 and cand not in COVERAGE_SKIP:
                source_vocab.add(_stem(cand))
    body = str(brief.get("body") or "")
    sentences = _split_sentences(body)
    for sent in sentences:
        words = _coverage_words(sent)
        if len(words) < 5:
            continue
        covered = sum(1 for w in words if w in source_vocab)
        if covered / len(words) < 0.60:
            reasons.append(
                f"unsupported background: sentence <60% covered by sources: {sent[:120]!r}..."
            )
            break  # one flag per brief is enough
    return reasons


def _numbers_with_context(text: str) -> list[tuple[str, set[str]]]:
    """Numbers with their surrounding content-word context."""
    tokens = re.findall(r"[A-Za-z0-9$%.,/-]+", text)
    out: list[tuple[str, set[str]]] = []
    for i, tok in enumerate(tokens):
        for m in _NUMBER_RE.finditer(tok):
            raw = m.group(0)
            if not re.search(r"\d", raw):
                continue
            norm = _norm_num(raw)
            if not norm:
                continue
            window = tokens[max(0, i - 5): i + 6]
            ctx = set(_content_words(" ".join(window)))
            # Drop the number itself from context when numeric.
            out.append((norm, ctx))
    return out


def _check_disagreement(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    members = _members(cluster)
    if len(members) < 2:
        return []
    per_source: list[set[str]] = []
    contexts: dict[str, set[str]] = {}
    for m in members:
        text = f"{m.get('headline') or ''} {m.get('excerpt') or ''}"
        nodate = _strip_dates(text)
        pairs = _numbers_with_context(nodate)
        nums = {n for n, _ in pairs}
        per_source.append(nums)
        for n, ctx in pairs:
            contexts.setdefault(n, set()).update(ctx)
    union = set().union(*per_source) if per_source else set()
    if len(union) < 2:
        return []
    # Candidate disagreements: distinct numbers from different sources with
    # overlapping context (same kind of number).
    body_nodate = _strip_dates(f"{brief.get('body') or ''} {brief.get('dek') or ''}")
    body_nums = _extract_numbers(body_nodate)
    body_floats: set[float] = set()
    for n in body_nums:
        try:
            body_floats.add(float(n))
        except ValueError:
            pass

    def _in_body(n: str) -> bool:
        if n in body_nums:
            return True
        try:
            return float(n) in body_floats
        except ValueError:
            return False

    distinct = sorted(union)
    for i in range(len(distinct)):
        for j in range(i + 1, len(distinct)):
            n1, n2 = distinct[i], distinct[j]
            # Must come from different sources (no single source has both).
            holders1 = {k for k, s in enumerate(per_source) if n1 in s}
            holders2 = {k for k, s in enumerate(per_source) if n2 in s}
            if not holders1 or not holders2:
                continue
            if holders1 == holders2 and len(holders1) == 1 and holders1 & holders2:
                # Both numbers in the same single source: not a cross-source
                # disagreement (still could be, but don't force).
                # Only skip when they share exactly the same sole holder
                # AND that holder is the only holder of both.
                same_sole = holders1 == holders2 and len(holders1) == 1
                if same_sole:
                    continue
            # Same-kind test: shared context or shared affected keyword.
            c1, c2 = contexts.get(n1, set()), contexts.get(n2, set())
            shared = c1 & c2
            same_kind = len(shared) >= 2 or bool(
                (c1 | c2) & AFFECTED_KEYWORDS and (c1 & AFFECTED_KEYWORDS or c2 & AFFECTED_KEYWORDS or len(shared) >= 1)
            )
            # Fallback: if both numbers are large counts (>10) and share any
            # content word, treat as same kind (covers 300 homes vs 500
            # customers sharing troy/water/break).
            if not same_kind and len(shared) >= 1:
                try:
                    if float(n1) > 10 and float(n2) > 10:
                        same_kind = True
                except ValueError:
                    pass
            if not same_kind:
                continue
            missing = [n for n in (n1, n2) if not _in_body(n)]
            if missing:
                return [
                    f"disagreement not attributed: sources give {n1} vs {n2} "
                    f"(shared context: {sorted(shared)[:4]}), body must contain both"
                ]
    return []


def _location_key(loc: Mapping[str, Any]) -> tuple[str, ...]:
    return (
        str(loc.get("country") or "").upper(),
        str(loc.get("admin1") or "").upper(),
        str(loc.get("admin2") or "").lower(),
        str(loc.get("city") or "").lower(),
        str(loc.get("metro") or "").lower(),
    )


def _check_locations(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    reasons: list[str] = []
    cluster_locs = [dict(l) for l in (cluster.get("locations", []) or []) if isinstance(l, Mapping)]
    cluster_keys = {_location_key(l) for l in cluster_locs}
    brief_locs = brief.get("locations", []) or []
    if not isinstance(brief_locs, list):
        return ["locations must be a list"]
    source_norm = _normalize_for_match(_source_text_headline_excerpt(cluster))
    # Display names from the cluster's resolved locations (human-readable
    # Places) also count: "New York" for US-NY, "Capital Region" for the
    # metro slug, etc. The prompt shows only display names, so the model
    # naturally outputs them.
    location_names = _cluster_location_names(cluster)
    for loc in brief_locs:
        if not isinstance(loc, Mapping):
            reasons.append("location entry must be an object")
            continue
        if _location_key(loc) in cluster_keys:
            continue
        # Gazetteer-name fallback: a city/admin2/metro named in the sources.
        names = [str(loc.get(k) or "") for k in ("city", "admin2", "metro", "admin1")]
        if any(n and _normalize_for_match(n) in source_norm for n in names if n):
            continue
        if any(n and _normalize_for_match(n) in location_names for n in names if n):
            continue
        # Country-only locations match any cluster with the same country.
        if set(loc.keys()) == {"country"} and any(
            str(c.get("country") or "").upper() == str(loc.get("country") or "").upper()
            for c in cluster_locs
        ):
            continue
        reasons.append(f"location not in cluster or sources: {dict(loc)}")
    return reasons


# Explicit place-containment copulas: "X is located in Y", "X, which is in
# Y", "X is in Y County". Bare locatives ("arrest occurred in Troy") are
# normal prose and NOT checked here.
_CONTAINMENT_RES = (
    re.compile(r"\b(?:is|are|was|were)\s+(?:located|situated)\s+in\b", re.IGNORECASE),
    re.compile(r"\b(?:which|that)\s+(?:is|are)\s+(?:located|situated\s+)?in\b", re.IGNORECASE),
    re.compile(r"\b(?:is|are|was|were)\s+in\s+[A-Z][A-Za-z.'\s]*?County\b"),
)
_COUNTY_RE = re.compile(r"([A-Z][A-Za-z.'-]*(?:\s+[A-Z][A-Za-z.'-]*)*\s+County)")


def _padded(text: str) -> str:
    return f" {text} "


def _check_place_containment(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    """Reject place-containment claims the sources never pair up.

    Production failure: a Poestenkill story briefed "The area is located in
    Albany County" because Albany County was the (mis-resolved) cluster
    location. The location allowlist correctly permits mentioning Albany
    County -- but asserting that X IS IN Y is a factual pairing that needs
    its own proof: the exact pairing stated in the sources, or a
    gazetteer-true pairing (Troy's admin2 really is Rensselaer County), or
    the same city+area pairing present in the cluster's locations. An
    unknown place (Poestenkill is not in the trimmed gazetteer) can never
    pass on geography alone -- state what happened there, not what contains
    it. Strict by design: true-but-unverifiable containment must be
    rephrased, never asserted.
    """
    reasons: list[str] = []
    city_areas, state_names = _gazetteer_geo()
    source_norm = _normalize_for_match(_source_text_headline_excerpt(cluster))
    # Cluster city -> its areas, for pairing rule (2).
    cluster_pairs: dict[str, set[str]] = {}
    admin1_map, _, _ = _gazetteer_lookups()
    for loc in (cluster.get("locations", []) or []):
        if not isinstance(loc, Mapping):
            continue
        city = _normalize_for_match(str(loc.get("city") or ""))
        if not city:
            continue
        areas = set(cluster_pairs.get(city, set()))
        for key in ("admin2", "metro"):
            norm = _normalize_for_match(str(loc.get(key) or ""))
            if norm:
                areas.add(norm)
        admin1 = str(loc.get("admin1") or "")
        if _normalize_for_match(admin1):
            areas.add(_normalize_for_match(admin1))
        if admin1 in admin1_map:
            areas.update(_normalize_for_match(a) for a in admin1_map[admin1])
        cluster_pairs[city] = areas
    text = f"{brief.get('body') or ''} {brief.get('dek') or ''}"
    for sent in _split_sentences(text):
        if not any(rx.search(sent) for rx in _CONTAINMENT_RES):
            continue
        norm = _padded(_normalize_for_match(sent))
        counties = {_normalize_for_match(m.group(1)) for m in _COUNTY_RE.finditer(sent)}
        counties.discard("")
        states = {s for s in state_names if _padded(s) in norm}
        mentions = [(m, "county") for m in counties] + [(m, "state") for m in states]
        if not mentions:
            continue
        # City mentions, minus ones overlapping a county/state mention
        # ("Albany" inside "Albany County" is not a standalone city cite).
        taken: list[tuple[int, int]] = []
        for m, _kind in mentions:
            start = 0
            while True:
                i = norm.find(_padded(m).strip(), start)
                if i < 0:
                    break
                taken.append((i, i + len(m)))
                start = i + 1
        cities: set[str] = set()
        for city in list(city_areas) + list(cluster_pairs):
            needle = f" {city} "
            start = 0
            found = False
            while True:
                i = norm.find(needle, start)
                if i < 0:
                    break
                span = (i + 1, i + 1 + len(city))
                if not any(s < span[1] and span[0] < e for s, e in taken):
                    found = True
                    break
                start = i + 1
            if not found:
                continue
            cities.add(city)
        for m, kind in mentions:
            ok = False
            for city in cities:
                if city in city_areas:
                    for admin2, a1names in city_areas[city]:
                        if kind == "county" and admin2 == m:
                            ok = True
                            break
                        if kind == "state" and m in a1names:
                            ok = True
                            break
                    if ok:
                        break
                if city in cluster_pairs and m in cluster_pairs[city]:
                    ok = True
                    break
                # Exact pairing stated in the sources ("poestenkill in
                # rensselaer county", "troy, new york").
                for pat in (f"{city} in {m}", f"{city} is located in {m}", f"{city}, {m}"):
                    if pat in source_norm:
                        ok = True
                        break
                if ok:
                    break
            if not ok:
                reasons.append(f"unsupported place containment not in sources: {m!r}")
                break  # one flag per brief is enough
        if reasons:
            break
    return reasons


# ---------------------------------------------------------------------------
# High-stakes outcome claims: death, criminal-justice results, injuries.
# ---------------------------------------------------------------------------

# Concept -> word-family regex. A brief term is supported when the SAME
# concept appears in the cluster's source text (headlines + excerpts), so
# faithful paraphrases pass (source "fatal" supports brief "died"; source
# "arrested" supports "arrest") while escalations ("injured" -> "died")
# and inventions fail. Stdlib regexes only, no NLP deps.
_OUTCOME_CONCEPTS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("death", re.compile(
        r"\b(?:dying|die[sd]?|dead(?:ly)?|deaths?|kill(?:s|ed|ing)?"
        r"|fatal(?:ly|ity|ities)?)\b",
        re.IGNORECASE)),
    ("arrest", re.compile(r"\barrest(?:s|ed|ing)?\b", re.IGNORECASE)),
    ("charge", re.compile(r"\bcharg(?:e|es|ed|ing)\b", re.IGNORECASE)),
    ("conviction", re.compile(r"\b(?:convict(?:s|ed|ion|ions)?|guilty)\b", re.IGNORECASE)),
    ("sentencing", re.compile(r"\bsentenc(?:e|es|ed|ing)\b", re.IGNORECASE)),
    ("custody", re.compile(r"\b(?:custody|custodial)\b", re.IGNORECASE)),
    ("indictment", re.compile(r"\bindict(?:s|ed|ment|ments)?\b", re.IGNORECASE)),
    ("arraignment", re.compile(r"\barraign(?:s|ed|ment|ments)?\b", re.IGNORECASE)),
    ("imprisonment", re.compile(r"\b(?:jail(?:s|ed)?|imprison(?:ed)?|prison)\b", re.IGNORECASE)),
    ("injury", re.compile(r"\binjur(?:y|ies|ed)?\b|\bwound(?:s|ed)?\b", re.IGNORECASE)),
)


def _check_outcome_claims(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    """Reject high-stakes outcome terms the sources never state.

    Production failure (story f50f7807554b049d): WTEN reported a Guard
    crew chief INJURED in a helicopter crash; the brief headlined that a
    veteran DIED. Saying someone died when the source says injured is the
    worst error a news app can make, so every death/legal/injury term in
    the headline, dek, or body must be backed by the same concept in the
    cluster's source headlines + excerpts. Strict by design (no negation
    exceptions): a true-but-unstated outcome must be dropped, never
    asserted.
    """
    reasons: list[str] = []
    source_text = " ".join(
        f"{m.get('headline') or ''} {m.get('excerpt') or ''}"
        for m in _members(cluster)
    )
    brief_text = " ".join(
        str(brief.get(k) or "") for k in ("headline", "dek", "body")
    )
    for concept, rx in _OUTCOME_CONCEPTS:
        found = sorted({m.group(0).lower() for m in rx.finditer(brief_text)})
        if not found:
            continue
        if not rx.search(source_text):
            reasons.append(
                "unsupported outcome claim not in sources: "
                f"{'/'.join(found)} ({concept})"
            )
    return reasons


# Event-localization assertions: "the crash occurred in X". The
# place-containment guard deliberately skips bare locatives ("arrest
# occurred in Troy" is normal prose), so this targeted check covers the
# occurred/happened/took-place-in shape instead.
_EVENT_LOCATION_RE = re.compile(
    r"\b(?:occurred|happened|took place|takes? place|taking place)"
    r"\s+in\s+(?:the\s+)?([A-Z][A-Za-z.'-]*(?:\s+[A-Z][A-Za-z.'-]*)*)"
)


def _check_event_location(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    """Reject event-location assertions the sources never localize.

    Production failure (story f50f7807554b049d): "The crash occurred in
    the Capital Region, according to the source" -- the source never said
    where the crash happened. The entity allowlist correctly permits
    mentioning the cluster metro, but asserting the EVENT happened there
    is a factual localization that needs source proof: the place must be
    named in the source headlines/excerpts, or be the cluster's own
    city/county/state/country (never the broad metro alone -- the Troy
    brief's "occurred in Rensselaer County" still passes while the
    Capital Region claim still fails).
    """
    reasons: list[str] = []
    source_norm = _normalize_for_match(_source_text_headline_excerpt(cluster))
    allowed = _cluster_location_names(cluster, include_metro=False)
    text = " ".join(
        str(brief.get(k) or "") for k in ("headline", "dek", "body")
    )
    for m in _EVENT_LOCATION_RE.finditer(text):
        place = _normalize_for_match(m.group(1))
        if not place:
            continue
        if place in source_norm:
            continue
        if place in allowed:
            continue
        reasons.append(f"unsupported event location not in sources: {m.group(1)!r}")
    return reasons


# Narrative-stitching meta phrasing: the model explicitly narrating that it
# is combining multiple reports ("Another story from WAMC ..."). A brief
# covers ONE event; stitching language proves unrelated items were merged
# upstream (or the model invented a second source). Reject on sight.
_STITCHING_PHRASES = (
    "another story",
    "another report from",
    "a separate story",
    "in other news",
)


def _check_narrative_stitching(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    reasons: list[str] = []
    text = " ".join(
        str(brief.get(k) or "") for k in ("headline", "dek", "body")
    ).lower()
    for phrase in _STITCHING_PHRASES:
        if phrase in text:
            reasons.append(
                f"narrative stitching: brief merges reports ({phrase!r}); "
                "one brief covers one event"
            )
            break  # one flag per brief is enough
    return reasons


def publication_outcome_reasons(
    brief: Mapping[str, Any], cluster: Mapping[str, Any]
) -> list[str]:
    """Publish-time re-check: outcome + event-location claims only.

    These two checks depend solely on the headline/dek/body and the
    cluster's source text/locations -- never on people, organizations, or
    sourceIds, which published stories don't carry. Full validation already
    ran at generation time in the newsroom; this narrow gate only guards
    against validator upgrades (stale briefs) with no false-downgrade risk
    from rebuilt-brief field drift.
    """
    return _check_outcome_claims(brief, cluster) + _check_event_location(brief, cluster)


def _min_body_words(cluster: Mapping[str, Any]) -> int:
    """Scaled length floor: thin RSS sources cannot honestly fill 60 words.

    min = max(30, min(60, 0.4 * source words)). Sources under ~75 words need
    only 30 honest words; the floor rises to 60 for rich multi-source
    clusters. The factor stays well under 1.0 so honest compression passes:
    a 33-word brief grounded in a 68-word excerpt is good writing, and the
    coverage guard (not the floor) is what catches padding. Max stays 220.
    """
    source_words = len(_source_text_headline_excerpt(cluster).split())
    return max(30, min(60, int(source_words * 0.4)))


def _check_length(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> list[str]:
    reasons: list[str] = []
    headline = str(brief.get("headline") or "")
    dek = str(brief.get("dek") or "")
    body = str(brief.get("body") or "")
    if len(headline) > 110:
        reasons.append(f"headline too long: {len(headline)} chars (max 110)")
    if len(dek) > 200:
        reasons.append(f"dek too long: {len(dek)} chars (max 200)")
    words = len(body.split())
    floor = _min_body_words(cluster)
    if not floor <= words <= 220:
        reasons.append(f"body must be {floor}-220 words, got {words}")
    return reasons


def _check_verbatim(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> list[str]:
    # Fields are checked separately (no cross-boundary grams): a 12-word run
    # must sit inside one brief field and inside one source field. This avoids
    # false positives where a dek ending ("... in Albany.") plus a body
    # opening ("Firefighters ...") mirrors the source headline->excerpt
    # boundary without copying 12 words from any single field.
    reasons: list[str] = []
    brief_fields = [str(brief.get(k) or "") for k in ("headline", "dek", "body")]
    for m in _members(cluster):
        rights = str(m.get("rightsMode") or "")
        if rights in ("PUBLIC_DOMAIN", "OPEN_LICENSE"):
            continue
        for src_field in (str(m.get("headline") or ""), str(m.get("excerpt") or "")):
            src_words = _words_for_verbatim(src_field)
            if len(src_words) < 12:
                continue
            src_grams = {tuple(src_words[i:i + 12]) for i in range(len(src_words) - 11)}
            for field_text in brief_fields:
                words = _words_for_verbatim(field_text)
                for i in range(len(words) - 11):
                    gram = tuple(words[i:i + 12])
                    if gram in src_grams:
                        # Quote the copied run so the single retry can target
                        # it ("rewrite this passage in your own words").
                        # Still strict: any 12-word run fails, no exceptions.
                        reasons.append(
                            f"verbatim copy: 12+ consecutive words from non-{rights} source "
                            f"{m.get('sourceId') or m.get('id')}: "
                            f"{' '.join(gram[:10])!r}..."
                        )
                        return reasons
    return reasons


_QUOTE_RES = (
    re.compile(r'"([^"]{3,})"'),
    re.compile(r"“([^”]{3,})”"),
)


def _check_quotes(brief: Mapping[str, Any], cluster: Mapping[str, Any]) -> list[str]:
    reasons: list[str] = []
    brief_text = " ".join(
        str(brief.get(k) or "") for k in ("headline", "dek", "body")
    )
    if not any(q in brief_text for q in ('"', "“", "”")):
        return []
    source_norm = _normalize_text(_source_text_headline_excerpt(cluster))
    quoted: list[str] = []
    for rx in _QUOTE_RES:
        quoted.extend(rx.findall(brief_text))
    if not quoted:
        return ["quotation marks present but no quoted text extracted"]
    for q in quoted:
        if _normalize_text(q) not in source_norm:
            reasons.append(f"fake quote not in sources: {q[:120]!r}")
    return reasons


# ---------------------------------------------------------------------------
# Top-level entry point
# ---------------------------------------------------------------------------

def validate_brief(
    brief: Mapping[str, Any],
    cluster: Mapping[str, Any],
) -> ValidationResult:
    """Run every deterministic check. Returns ok + rejection reasons."""
    reasons: list[str] = []
    if not isinstance(brief, Mapping):
        return ValidationResult(ok=False, reasons=["brief must be an object"])
    reasons.extend(_check_schema(brief))
    reasons.extend(_check_source_ids(brief, cluster))
    reasons.extend(_check_entities(brief, cluster))
    reasons.extend(_check_numbers_and_dates(brief, cluster))
    reasons.extend(_check_background_coverage(brief, cluster))
    reasons.extend(_check_disagreement(brief, cluster))
    reasons.extend(_check_locations(brief, cluster))
    reasons.extend(_check_place_containment(brief, cluster))
    reasons.extend(_check_outcome_claims(brief, cluster))
    reasons.extend(_check_event_location(brief, cluster))
    reasons.extend(_check_length(brief, cluster))
    reasons.extend(_check_verbatim(brief, cluster))
    reasons.extend(_check_quotes(brief, cluster))
    reasons.extend(_check_narrative_stitching(brief, cluster))
    return ValidationResult(ok=not reasons, reasons=reasons)
