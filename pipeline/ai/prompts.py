"""Prompts for the AI newsroom (Phase 3).

System prompt encodes the spec's "automated news editor" rules plus the
political-neutrality rules. The user message holds only rights-permitted
material per the Phase 1 matrix: headlines, permitted excerpts, publishers,
timestamps, source IDs.

Thinking is disabled at the API layer
(chat_template_kwargs.enable_thinking=false); no chain-of-thought is
produced or stored.
"""
from __future__ import annotations

from typing import Any, Mapping

from ..rights import may_feed_ai_text

# Single taxonomy (schemas/taxonomy.json). The model must never invent a
# label outside this list; the validator rejects anything else.
CATEGORY_IDS = (
    "local",
    "public-safety",
    "business",
    "technology",
    "science",
    "sports",
    "entertainment",
    "politics",
    "health",
    "environment",
    "travel",
    "weather",
)

SYSTEM_PROMPT = """You are an automated news editor.
Write a SHORT news brief using ONLY facts stated in the supplied SOURCES and clusterLocations. Every sentence must be grounded in that material. Fewer sentences are fine: 60 words beats 150 words with filler.
NEVER add background, context, speculation, or filler. BANNED filler (never write these or anything like them):
- "Details ... are not yet available / are still developing / have not been released"
- "part of a broader initiative / effort / campaign"
- "The case is being handled by ..." / "The investigation is ongoing ..." unless a source says so
- generic closers ("The report highlights the financial strain ...", "officials continue to monitor ...")
If the sources say little, write a short brief and stop. Do not pad to fill space.
DO NOT: invent facts, invent quotes, invent names, invent dates, infer motives, make unsupported conclusions, change numeric values, make political judgments.
PARAPHRASE: never copy 12 or more consecutive words from any source. Rewrite in your own words.
Clearly distinguish uncertainty. If sources disagree, say they disagree and attribute each version (name each source).
Use neutral journalistic language. Do not endorse candidates, parties, or positions. Do not give voting advice. Do not rank parties or candidates. Attribute political claims to their sources.
Rules:
- Headline: at most 110 characters, plain text, no quotation marks unless quoting a source verbatim.
- Dek: one sentence, at most 200 characters, no new facts beyond the body.
- Body: 30 to 220 words, newspaper style. Match the sources: thin sources get a short brief (30-60 words); rich multi-source clusters get the fuller 60+ word treatment. Every name, number, date, and quote must come from the sources (headlines, excerpts, publishers, timestamps) or clusterLocations. Never pad with filler to hit a length.
- Category: exactly one of: local, public-safety, business, technology, science, sports, entertainment, politics, health, environment, travel, weather.
- Locations: only places named in the sources or listed in clusterLocations (city/county/state/country). Use human-readable names (New York, Albany County, Troy). Never output raw codes or slugs like US-NY or us-ny-capital-region. Never invent coordinates.
- People/organizations: only names appearing in the sources, copied EXACTLY as written (if sources say "President Donald Trump", write that -- never shorten to "President Trump").
- sourceIds: every ID you list must be one of the supplied source entry IDs. Cite all sources you used.
- If a fact appears in only one source, attribute it ("according to ...").
- If sources disagree on a number, include BOTH values with attribution. Never pick one silently.
- No quotation marks unless the quoted text appears verbatim in a source.
- Return strict structured JSON only, no prose outside the JSON object.

Example of GOOD grounded writing (short, no filler):
SOURCES: WNYT "Fire on Central Avenue in Albany" / "Firefighters responded to a blaze on Central Avenue in Albany. About 14 residents were displaced." + CBS6 "Albany blaze prompts closures".
GOOD body (68 words): "Firefighters responded to a blaze on Central Avenue in Albany, according to WNYT and CBS6. About 14 residents were displaced, WNYT reported. Crews closed Central Avenue while they worked the scene, according to CBS6. The closure affected the Central Avenue area of Albany. WNYT said the displaced residents were from the immediate area. CBS6 reported the street closure during the response."

Example of BAD padding (never do this):
BAD: "Details about the investigation are not yet available. The project is part of a broader initiative to improve infrastructure. The case is being handled by the police." (invented background, zero source support)."""


FACTCHECK_SYSTEM = """You check whether a news brief is fully supported by its sources.
List any statements in the story not supported by the source facts.
Return JSON {"unsupported": []} with one string per unsupported statement.
If everything is supported, return {"unsupported": []}."""


def _permitted_source_view(member: Mapping[str, Any]) -> dict[str, Any]:
    """Rights-filtered view of one cluster member for the model.

    - METADATA_ONLY: headline only (no excerpt).
    - LINK_ONLY: headline only, and the orchestrator never uses a
      LINK_ONLY-only cluster as a brief basis (see newsroom.py).
    - Others: headline + permitted excerpt.
    """
    rights = str(member.get("rightsMode") or "METADATA_ONLY")
    view: dict[str, Any] = {
        "id": str(member.get("id") or member.get("url") or ""),
        "sourceId": str(member.get("sourceId") or ""),
        "publisher": str(member.get("publisher") or ""),
        "headline": str(member.get("headline") or ""),
        "publishedAt": str(member.get("publishedAt") or ""),
        "rightsMode": rights,
    }
    if may_feed_ai_text(rights) and rights != "METADATA_ONLY":
        excerpt = str(member.get("excerpt") or "").strip()
        if excerpt:
            view["excerpt"] = excerpt[:2000]
    return view


def build_user_message(cluster: Mapping[str, Any]) -> str:
    """User message: only rights-permitted material, nothing else."""
    import json

    members = list(cluster.get("members", []) or [])
    # Deterministic order: by publishedAt then id.
    members = sorted(
        members,
        key=lambda m: (str(m.get("publishedAt") or ""), str(m.get("id") or "")),
    )
    sources = [_permitted_source_view(m) for m in members]
    category_hint = str(cluster.get("category") or "local")
    locations = list(cluster.get("locations", []) or [])
    payload = {
        "SOURCES": sources,
        "categoryHint": category_hint,
        "clusterLocations": locations,
    }
    return (
        "SOURCES (use only this material):\n"
        + json.dumps(payload, indent=1)
        + f"\n\nCategory hint: {category_hint}. "
        + "You must still pick the single best category from the allowed list."
    )


def build_messages(cluster: Mapping[str, Any]) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": build_user_message(cluster)},
    ]


def build_retry_messages(
    cluster: Mapping[str, Any],
    previous_brief_json: str,
    failure_reason: str,
) -> list[dict[str, str]]:
    """One regeneration (the single retry): previous output + the specific
    violation appended, with targeted fix instructions per violation type."""
    base_user = build_user_message(cluster)
    hints = []
    low = failure_reason.lower()
    if "verbatim" in low:
        hints.append(
            "For verbatim copy: rewrite EVERY sentence from scratch in your own "
            "words -- change the sentence structure, not just a few words."
        )
    if "name/span" in low or "people" in low or "organization" in low:
        hints.append(
            "For unsupported names: copy personal and place names EXACTLY as "
            "written in the sources; drop any name you cannot find there."
        )
    if "background" in low:
        hints.append(
            "For unsupported background: delete the flagged sentence and "
            "replace it (if needed) with a sentence built only from source "
            "words, or drop it entirely -- a shorter brief is fine."
        )
    if "date" in low or "number" in low:
        hints.append(
            "For dates/numbers: copy the exact values (and their format "
            "context) from the sources; never round, shorten, or reformat "
            "names around them."
        )
    if "body must be" in low:
        hints.append(
            "For length: add one more grounded sentence from the sources, or "
            "trim filler -- never pad with background."
        )
    retry_user = (
        base_user
        + "\n\nYour previous output FAILED validation for this reason:\n"
        + failure_reason[:1500]
        + "\nPrevious output:\n"
        + previous_brief_json[:4000]
        + "\nFix ONLY the flagged problems. Keep everything else grounded in the sources."
        + (" " + " ".join(hints) if hints else "")
    )
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": retry_user},
    ]


def build_factcheck_messages(
    brief_body: str, source_text: str
) -> list[dict[str, str]]:
    import json

    # Short prompt: same server, small second call (throughput). The brief
    # body is at most ~4000 chars; 2000 + 4000 chars of context is enough to
    # judge support without re-sending the whole cluster.
    user = (
        "STORY:\n" + brief_body[:2000]
        + "\n\nSOURCE FACTS:\n" + source_text[:4000]
        + '\n\nList any statements in the story not supported by the source facts. Return JSON {"unsupported": []}.'
    )
    return [
        {"role": "system", "content": FACTCHECK_SYSTEM},
        {"role": "user", "content": user},
    ]


def output_fields_doc() -> dict[str, str]:
    """Documents the model's output fields.

    The pipeline overwrites generatedAt/aiModel itself instead of trusting
    the model (see newsroom.py).
    """
    return {
        "headline": "at most 110 chars",
        "dek": "at most 200 chars",
        "body": "30-220 words (short when sources are thin, never padded)",
        "category": "|".join(CATEGORY_IDS),
        "locations": "places named in sources only",
        "people": "names in sources only",
        "organizations": "names in sources only",
        "sourceIds": "subset of supplied IDs",
        "generatedAt": "overwritten by pipeline",
        "aiModel": "overwritten by pipeline",
        "confidence": "0-1",
    }
