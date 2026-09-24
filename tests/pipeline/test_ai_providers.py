"""Provider chain tests (offline, mocked transport -- never a real LLM)."""
import json

from pipeline.ai.prompts import build_factcheck_messages, build_user_message
from pipeline.ai.providers import (
    CloudflareWorkersAIProvider,
    LocalLlamaProvider,
    SourceCardProvider,
    build_ai_story,
)
from pipeline.ai.validate import validate_brief


def _cluster(rights_a="RSS_EXCERPT_ALLOWED", rights_b="METADATA_ONLY"):
    return {
        "eventId": "chain123chain1234",
        "members": [
            {
                "id": "a1",
                "sourceId": "src-a",
                "publisher": "WNYT",
                "headline": "Fire on Central Avenue in Albany",
                "excerpt": "Firefighters responded to a blaze on Central Avenue in Albany. About 14 residents were displaced.",
                "url": "https://example.com/a1",
                "publishedAt": "2026-09-23T09:05:00Z",
                "rightsMode": rights_a,
            },
            {
                "id": "b2",
                "sourceId": "src-b",
                "publisher": "CBS6",
                "headline": "Albany blaze prompts closures",
                "excerpt": "",
                "url": "https://example.com/b2",
                "publishedAt": "2026-09-23T09:40:00Z",
                "rightsMode": rights_b,
            },
        ],
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "category": "local",
        "confidence": "HIGH",
        "confidenceTier": "high",
        "score": 0.9,
        "status": "new",
        "version": 1,
        "firstSeen": "2026-09-23T09:05:00Z",
        "lastSeen": "2026-09-23T09:40:00Z",
        "headline": "Fire on Central Avenue in Albany",
        "sources": [
            {"publisher": "WNYT", "headline": "Fire on Central Avenue in Albany",
             "url": "https://example.com/a1", "publishedAt": "2026-09-23T09:05:00Z",
             "rightsMode": rights_a, "excerpt": "Firefighters responded."},
            {"publisher": "CBS6", "headline": "Albany blaze prompts closures",
             "url": "https://example.com/b2", "publishedAt": "2026-09-23T09:40:00Z",
             "rightsMode": rights_b},
        ],
    }


def _good_brief_payload():
    # All content words come from the source headlines/excerpts/publishers
    # (plus stopwords/allowlisted boilerplate) so the coverage guard passes.
    # No 12-word verbatim runs; 14 is the only number and it is grounded.
    body = (
        "Firefighters responded to a blaze on Central Avenue in Albany, according to WNYT "
        "and CBS6. About 14 residents were displaced by the Central Avenue blaze in Albany. "
        "The Albany blaze on Central Avenue displaced residents, according to WNYT. "
        "CBS6 reported the Central Avenue blaze in Albany. "
        "Firefighters responded in Albany as residents were displaced. "
        "About 14 residents were displaced in Albany. "
        "The Central Avenue blaze in Albany displaced about 14 residents, according to WNYT and CBS6."
    )
    assert 60 <= len(body.split()) <= 220, len(body.split())
    return {
        "headline": "Central Avenue fire displaces residents in Albany",
        "dek": "Crews closed the street during the morning response.",
        "body": body,
        "category": "local",
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "people": [],
        "organizations": [],
        "sourceIds": ["a1", "b2"],
        "aiModel": "test",
        "confidence": 0.8,
    }


def _envelope(brief: dict) -> dict:
    return {"choices": [{"message": {"content": json.dumps(brief)}}]}


def test_local_provider_success_with_mock():
    provider = LocalLlamaProvider(post_fn=lambda payload: _envelope(_good_brief_payload()))
    brief, raw, err = provider.generate(_cluster())
    assert err is None and isinstance(brief, dict)
    assert validate_brief(brief, _cluster()).ok


def test_local_transport_failure_returns_none():
    def _boom(payload):
        raise ConnectionError("server down")

    provider = LocalLlamaProvider(post_fn=_boom)
    brief, raw, err = provider.generate(_cluster())
    assert brief is None and err is not None


def test_workers_429_returns_none():
    import urllib.error

    def _429(payload):
        raise urllib.error.HTTPError(
            "https://example.invalid", 429, "Too Many Requests", {}, None
        )

    provider = CloudflareWorkersAIProvider(
        account_id="x", api_token="y", post_fn=_429
    )
    assert provider.enabled
    brief, raw, err = provider.generate(_cluster())
    assert brief is None


def test_workers_disabled_without_secrets():
    provider = CloudflareWorkersAIProvider(account_id="", api_token="")
    assert not provider.enabled
    brief, raw, err = provider.generate(_cluster())
    assert brief is None


def test_source_card_has_headline_link_no_fabrication():
    card = SourceCardProvider().build_card(_cluster())
    assert card["aiGenerated"] is False
    assert card["id"] == "chain123chain1234"
    assert card["headline"]
    assert card["sources"] and all(s["url"].startswith("http") for s in card["sources"])
    # No fabricated summary: body absent or empty, excerpt only when allowed.
    assert card.get("body", "") == "" or "body" not in card


def test_prompt_holds_only_permitted_material():
    cluster = _cluster()
    # Give the METADATA_ONLY member a decoy excerpt: it must be stripped.
    for m in cluster["members"]:
        if m["rightsMode"] == "METADATA_ONLY":
            m["excerpt"] = "Should never reach the model for METADATA_ONLY."
    msg = build_user_message(cluster)
    # METADATA_ONLY excerpt must never reach the model.
    assert "Should never reach the model" not in msg
    # Headlines, publishers, timestamps, IDs are allowed.
    assert "WNYT" in msg and "CBS6" in msg
    assert "2026-09-23T09:05:00Z" in msg
    assert "Firefighters responded to a blaze" in msg  # RSS excerpt allowed


def test_link_only_never_basis():
    from pipeline.newsroom import _ai_usable

    link_only = {
        "eventId": "linkonly1",
        "members": [
            {"id": "l1", "sourceId": "s", "publisher": "P",
             "headline": "Something happened", "url": "https://example.com/l1",
             "publishedAt": "2026-09-23T09:00:00Z", "rightsMode": "LINK_ONLY"},
        ],
    }
    assert not _ai_usable(link_only)
    card = SourceCardProvider().build_card(link_only)
    assert card["aiGenerated"] is False


def test_ai_story_carries_attribution_and_model_overwrite():
    from datetime import datetime, timezone

    brief = _good_brief_payload()
    brief["aiModel"] = "untrusted-model-name"
    brief["generatedAt"] = "1999-01-01T00:00:00Z"
    story = build_ai_story(
        brief, _cluster(), model_name="Qwen3-4B-Q4_K_M",
        now=datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc),
    )
    # Pipeline overwrites generatedAt/aiModel instead of trusting the model.
    assert story["aiModel"] == "Qwen3-4B-Q4_K_M"
    assert story["generatedAt"].startswith("2026-09-23T12:00")
    assert story["aiGenerated"] is True
    assert len(story["sources"]) == 2


def test_retry_messages_carry_targeted_hints():
    from pipeline.ai.prompts import SYSTEM_PROMPT, build_retry_messages

    cluster = _cluster()
    verbatim = build_retry_messages(cluster, "{}", "verbatim copy: 12+ words")[1]["content"]
    assert "rewrite EVERY sentence from scratch" in verbatim
    bg = build_retry_messages(cluster, "{}", "unsupported background: sentence")[1]["content"]
    assert "shorter brief is fine" in bg
    # Prompt bans raw location codes/slugs and shortened personal names.
    assert "US-NY" in SYSTEM_PROMPT and "EXACTLY as written" in SYSTEM_PROMPT


def _factcheck_envelope(unsupported: list[str]) -> dict:
    return {"choices": [{"message": {"content": json.dumps({"unsupported": unsupported})}}]}


def test_factcheck_provider_returns_unsupported_claims():
    expected = ["The fire started at midnight.", "12 casualties reported."]

    provider = LocalLlamaProvider(
        post_fn=lambda payload: _envelope(_good_brief_payload()),
        factcheck_post_fn=lambda payload: _factcheck_envelope(expected),
    )
    unsupported, raw, err = provider.generate_factcheck(
        build_factcheck_messages("story body", "source facts")
    )
    assert err is None
    assert unsupported == expected
    assert "unsupported" in raw


def test_factcheck_provider_empty_list_means_supported():
    provider = LocalLlamaProvider(
        factcheck_post_fn=lambda payload: _factcheck_envelope([]),
    )
    unsupported, raw, err = provider.generate_factcheck(
        [{"role": "user", "content": "story body"}]
    )
    assert err is None
    assert unsupported == []


def test_factcheck_provider_transport_failure_is_fail_open():
    def _boom(payload):
        raise ConnectionError("server down")

    provider = LocalLlamaProvider(factcheck_post_fn=_boom)
    unsupported, raw, err = provider.generate_factcheck(
        [{"role": "user", "content": "story body"}]
    )
    assert unsupported is None and err is not None
    assert provider.last_error is not None


def test_factcheck_provider_malformed_response_is_error():
    provider = LocalLlamaProvider(
        factcheck_post_fn=lambda payload: {"choices": [{"message": {"content": "not json"}}]},
    )
    unsupported, raw, err = provider.generate_factcheck(
        [{"role": "user", "content": "story body"}]
    )
    assert unsupported is None and err is not None


def test_factcheck_provider_rejects_non_string_items():
    provider = LocalLlamaProvider(
        factcheck_post_fn=lambda payload: _factcheck_envelope(["ok", 42]),
    )
    unsupported, raw, err = provider.generate_factcheck(
        [{"role": "user", "content": "story body"}]
    )
    assert unsupported is None and err is not None
