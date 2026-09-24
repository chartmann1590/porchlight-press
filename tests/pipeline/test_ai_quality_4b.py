"""AI newsroom quality 4B: offline unit tests for each change (mocked, no model).

Covers:
1. Model choice always uses 4B primary (overflow switch disabled).
2. Prompt leaks: PLACES display names only, no raw codes/slugs, no
   "cluster locations" phrasing, prompt text not echoable as attribution.
3. Verbatim-only deterministic repair is the single retry (no extra retries).
4. max_tokens sized so JSON can't truncate + dek limit in prompt, no silent clamp.
"""
import json

from pipeline.ai import PRIMARY_MODEL
from pipeline.ai.prompts import (
    SYSTEM_PROMPT,
    _places_display_names,
    build_user_message,
    build_verbatim_repair_messages,
)
from pipeline.ai.providers import (
    BRIEF_MAX_TOKENS,
    _is_verbatim_only,
    try_brief_with_retry,
)
from pipeline.ai.validate import ValidationResult, validate_brief
from pipeline.newsroom import choose_model


def _cfg():
    return {"primaryModel": PRIMARY_MODEL, "fallbackModel": "Qwen3-1.7B-Q8_0",
            "overflowThreshold": 9999}


def test_always_primary_even_when_huge_queue():
    assert choose_model(0, _cfg()) == PRIMARY_MODEL
    assert choose_model(50, _cfg()) == PRIMARY_MODEL
    assert choose_model(51, _cfg()) == PRIMARY_MODEL
    assert choose_model(500, _cfg()) == PRIMARY_MODEL
    assert choose_model(500, _cfg(), override="custom") == "custom"


def test_places_display_names_only():
    locs = [
        {"country": "US", "admin1": "US-NY", "city": "Albany",
         "metro": "us-ny-capital-region"},
        {"country": "US", "admin1": "US-NY", "admin2": "Rensselaer County",
         "city": "Troy"},
    ]
    names = _places_display_names(locs)
    assert "Albany" in names
    assert "Troy" in names
    assert "Rensselaer County" in names
    assert "New York" in names
    assert "Capital Region" in names
    lowered = " ".join(names).lower()
    assert "us-ny" not in lowered
    assert "us_ny" not in lowered


def test_user_message_has_no_raw_codes_or_internals():
    cluster = {
        "members": [{
            "id": "m1", "sourceId": "s1", "publisher": "WNYT",
            "headline": "Fire on Central Avenue in Albany",
            "excerpt": "Firefighters responded to a blaze on Central Avenue in Albany.",
            "url": "https://example.com/m1",
            "publishedAt": "2026-09-23T09:05:00Z",
            "rightsMode": "RSS_EXCERPT_ALLOWED",
        }],
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany",
                       "metro": "us-ny-capital-region"}],
        "category": "local",
    }
    msg = build_user_message(cluster)
    low = msg.lower()
    assert "us-ny" not in low
    assert "clusterlocations" not in low.replace(" ", "")
    assert "cluster locations" not in low
    assert "PLACES" in msg
    assert "Albany" in msg
    assert "New York" in msg
    assert "Capital Region" in msg


def test_system_prompt_dek_limit_and_no_leaks():
    assert "at most 200 characters" in SYSTEM_PROMPT
    assert "US-NY" not in SYSTEM_PROMPT
    assert "us-ny" not in SYSTEM_PROMPT.lower()
    assert "cluster locations" not in SYSTEM_PROMPT.lower()
    assert "clusterlocations" not in SYSTEM_PROMPT.lower().replace(" ", "")


def test_validator_bans_prompt_echo_attribution():
    cluster = {
        "eventId": "echo000000000001",
        "members": [{
            "id": "a1", "sourceId": "s-a", "publisher": "WNYT",
            "headline": "Fire breaks out on Central Avenue in Albany, crews on scene",
            "excerpt": "Firefighters responded to a blaze on Central Avenue in Albany on Tuesday. About 300 homes lost water service after a main break on River Street.",
            "url": "https://example.com/a1",
            "publishedAt": "2026-09-23T09:05:00Z",
            "rightsMode": "RSS_EXCERPT_ALLOWED",
        }],
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "category": "local", "confidenceTier": "high", "score": 0.8,
        "status": "new", "version": 1,
        "firstSeen": "2026-09-23T09:05:00Z", "lastSeen": "2026-09-23T09:40:00Z",
    }

    def _brief_with_body(body):
        return {
            "headline": "Central Avenue fire disrupts service in Albany",
            "dek": "Officials report closures as crews respond in Albany.",
            "body": body,
            "category": "local",
            "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
            "people": [], "organizations": [],
            "sourceIds": ["a1"], "aiModel": "test", "confidence": 0.7,
        }

    base = (
        "On Tuesday, firefighters responded to a blaze on Central Avenue in Albany, "
        "according to WNYT. City officials said about 300 homes lost water service. "
        "The main break was on River Street. Crews closed Central Avenue while they "
        "worked the scene, according to WNYT. Firefighters responded in Albany as "
        "crews closed Central Avenue. About 300 homes lost water service in Albany. "
    )
    for leak in (
        "The incident occurred in Troy, US-NY, as per cluster locations.",
        "The incident occurred in Albany, as per the Places.",
        "Details are clear, according to the same source, with crews on scene in Albany.",
        "The event happened, according to the Places, with crews on scene in Albany.",
    ):
        bad = _brief_with_body(base + " " + leak)
        result = validate_brief(bad, cluster)
        assert not result.ok, leak


def test_is_verbatim_only():
    assert _is_verbatim_only(ValidationResult(ok=False, reasons=["verbatim copy: x"]))
    assert _is_verbatim_only(ValidationResult(
        ok=False, reasons=["verbatim copy: x", "verbatim copy: y"]))
    assert not _is_verbatim_only(ValidationResult(ok=False, reasons=[]))
    assert not _is_verbatim_only(ValidationResult(
        ok=False, reasons=["verbatim copy: x", "dek too long: 204 chars (max 200)"]))
    assert not _is_verbatim_only(ValidationResult(
        ok=False, reasons=["unsupported background: invented filler"]))


def test_verbatim_repair_message_is_targeted():
    cluster = {
        "members": [{
            "id": "m1", "sourceId": "s1", "publisher": "WNYT",
            "headline": "Fire on Central Avenue in Albany",
            "excerpt": "Firefighters responded to a blaze.",
            "url": "https://example.com/m1",
            "publishedAt": "2026-09-23T09:05:00Z",
            "rightsMode": "RSS_EXCERPT_ALLOWED",
        }],
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "category": "local",
    }
    msgs = build_verbatim_repair_messages(
        cluster, '{"headline":"x"}', "verbatim copy: 12+ words from source s1")
    assert len(msgs) == 2
    body = msgs[1]["content"]
    assert "reword ONLY the flagged sentences" in body
    assert "at most 200 chars" in body
    assert "us-ny" not in body.lower()


def _good_brief(source_ids=("a1", "b2")):
    body = (
        "Firefighters responded to a blaze on Central Avenue in Albany, according to WNYT "
        "and CBS6. About 14 residents were displaced by the Central Avenue blaze in Albany. "
        "The Albany blaze on Central Avenue displaced residents, according to WNYT. "
        "CBS6 reported the Central Avenue blaze in Albany. "
        "Firefighters responded in Albany as residents were displaced. "
        "About 14 residents were displaced in Albany. "
        "The Central Avenue blaze in Albany displaced about 14 residents, according to WNYT and CBS6."
    )
    return {
        "headline": "Central Avenue fire displaces residents in Albany",
        "dek": "Crews closed the street during the morning response.",
        "body": body,
        "category": "local",
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "people": [], "organizations": [],
        "sourceIds": list(source_ids), "aiModel": "test", "confidence": 0.8,
    }


def _cluster_for_retry():
    return {
        "eventId": "chain123chain1234",
        "members": [
            {"id": "a1", "sourceId": "src-a", "publisher": "WNYT",
             "headline": "Fire on Central Avenue in Albany",
             "excerpt": "Firefighters responded to a blaze on Central Avenue in Albany. About 14 residents were displaced.",
             "url": "https://example.com/a1",
             "publishedAt": "2026-09-23T09:05:00Z",
             "rightsMode": "RSS_EXCERPT_ALLOWED"},
            {"id": "b2", "sourceId": "src-b", "publisher": "CBS6",
             "headline": "Albany blaze prompts closures",
             "excerpt": "",
             "url": "https://example.com/b2",
             "publishedAt": "2026-09-23T09:40:00Z",
             "rightsMode": "METADATA_ONLY"},
        ],
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "category": "local", "confidenceTier": "high", "score": 0.9,
        "status": "new", "version": 1,
        "firstSeen": "2026-09-23T09:05:00Z", "lastSeen": "2026-09-23T09:40:00Z",
    }


def test_verbatim_only_uses_repair_path_and_single_retry():
    seen_messages = []
    calls = []

    good = _good_brief()
    # Make first attempt verbatim-only: copy 15 consecutive words from the
    # first excerpt ("Firefighters ... displaced"). Punctuation/case are
    # ignored by the verbatim check, so this is a 12+ verbatim run.
    bad = dict(good)
    bad["body"] = (
        "Firefighters responded to a blaze on Central Avenue in Albany about 14 "
        "residents were displaced, according to WNYT and CBS6. The Albany blaze on "
        "Central Avenue displaced residents, according to WNYT. CBS6 reported the "
        "Central Avenue blaze in Albany. Firefighters responded in Albany as residents "
        "were displaced. About 14 residents were displaced in Albany. The Central "
        "Avenue blaze in Albany displaced about 14 residents, according to WNYT and CBS6."
    )
    # Sanity: first attempt must fail verbatim-only (otherwise the test is vacuous).
    from pipeline.ai.validate import validate_brief as _vb
    _r0 = _vb(bad, _cluster_for_retry())
    assert not _r0.ok and all("verbatim" in r.lower() for r in _r0.reasons), _r0.reasons

    class _Fake:
        def generate(self, cluster):
            calls.append("generate")
            return dict(bad), json.dumps(bad), None

        def generate_with_messages(self, messages, temperature=None):
            calls.append("retry")
            seen_messages.append(messages)
            return dict(good), json.dumps(good), None

    brief, result, _raw, err = try_brief_with_retry(_Fake(), _cluster_for_retry())
    assert brief is not None and result is not None and result.ok, err
    assert calls == ["generate", "retry"]  # exactly one retry, no extras
    assert len(seen_messages) == 1
    assert "reword ONLY the flagged sentences" in seen_messages[0][1]["content"]


def test_non_verbatim_failure_does_not_use_repair_path():
    seen_messages = []

    good = _good_brief()
    bad = dict(good)
    bad["people"] = ["Invented Person XYZ"]
    bad_raw = json.dumps(bad)

    class _Fake:
        def generate(self, cluster):
            return dict(bad), bad_raw, None

        def generate_with_messages(self, messages, temperature=None):
            seen_messages.append(messages)
            return dict(good), json.dumps(good), None

    brief, result, _raw, _err = try_brief_with_retry(_Fake(), _cluster_for_retry())
    assert brief is not None and result.ok
    assert len(seen_messages) == 1
    assert "reword ONLY the flagged sentences" not in seen_messages[0][1]["content"]


def test_max_tokens_sized_and_dek_not_silently_clamped():
    # 700 tokens covers an honest 220-word brief + JSON wrapper; 400 truncated.
    assert BRIEF_MAX_TOKENS >= 600
    assert BRIEF_MAX_TOKENS == 700
    # Validator still rejects over-long deks (no silent clamp to pass).
    cluster = _cluster_for_retry()
    bad = _good_brief()
    bad["dek"] = "y" * 201
    result = validate_brief(bad, cluster)
    assert not result.ok
    assert any("dek too long" in r for r in result.reasons)
