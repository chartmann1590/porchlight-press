"""Provider abstraction for the AI newsroom (Phase 3).

Chain: local llama-server -> Cloudflare Workers AI (if configured) -> source card.
Portable: stdlib HTTP only (urllib), no GitHub-specific logic. Tests inject a
fake ``post_fn`` so no real LLM is ever downloaded or run in unit tests.
"""
from __future__ import annotations

import json
import os
import urllib.request
from datetime import datetime, timezone
from typing import Any, Callable, Mapping, Protocol

from .prompts import build_messages, build_retry_messages
from .validate import (
    ValidationResult,
    parse_brief_json,
    parse_factcheck_json,
    validate_brief,
)

PRIMARY_MODEL = "Qwen3-4B-Q4_K_M"
FALLBACK_MODEL = "Qwen3-1.7B-Q8_0"

PostFn = Callable[[dict[str, Any]], dict[str, Any]]


class BriefProvider(Protocol):
    def generate(self, cluster: Mapping[str, Any]) -> tuple[dict[str, Any] | None, str, str | None]:
        """Return (brief_dict|None, raw_json, error|None)."""
        ...


def _load_json_schema(filename: str) -> dict[str, Any]:
    from pathlib import Path

    schema_path = Path(__file__).resolve().parent.parent.parent / "schemas" / filename
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    # llama.cpp consumes the JSON schema; drop meta keys it doesn't need.
    schema.pop("$schema", None)
    schema.pop("$id", None)
    return schema


def _brief_json_schema() -> dict[str, Any]:
    return _load_json_schema("ai-brief.schema.json")


def _factcheck_json_schema() -> dict[str, Any]:
    return _load_json_schema("ai-factcheck.schema.json")


def _json_post_fn(
    base_url: str, timeout_seconds: int, schema: dict[str, Any] | None,
    schema_name: str = "output", max_tokens: int = 800,
) -> PostFn:
    def _post(payload: dict[str, Any]) -> dict[str, Any]:
        body = dict(payload)
        # Grammar-constrained, non-thinking output (proven in ai-feasibility.yml).
        body.setdefault("temperature", 0.2)
        body.setdefault("max_tokens", max_tokens)
        if schema is not None:
            body["response_format"] = {
                "type": "json_schema",
                "json_schema": {"name": schema_name, "schema": schema},
            }
        body["chat_template_kwargs"] = {"enable_thinking": False}
        req = urllib.request.Request(
            base_url.rstrip("/") + "/v1/chat/completions",
            json.dumps(body).encode("utf-8"),
            {"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:  # noqa: S310
            return json.loads(resp.read().decode("utf-8", errors="replace"))

    return _post


# Throughput (MASTER_PLAN §11: ~50 briefs/25-min run, 45-min job cap):
# live briefs run 30-70 words (~50-120 tokens + ~80 JSON wrapper), so 400
# bounds runaway generations (~36 s worst decode at 11 tok/s) instead of 800
# (~73 s). A truncated runaway fails JSON validation and takes the single
# retry -- still validated, never published raw. Factcheck answers
# {"unsupported": [...]} in a few dozen tokens. Both stay
# grammar-constrained with thinking disabled (see _json_post_fn). One retry
# max in try_brief_with_retry: a first-try accept costs one generation
# (~25 s); only failures pay for the second.
BRIEF_MAX_TOKENS = 400
FACTCHECK_MAX_TOKENS = 128


def _brief_post_fn(base_url: str, timeout_seconds: int) -> PostFn:
    return _json_post_fn(base_url, timeout_seconds, _brief_json_schema(), "brief", BRIEF_MAX_TOKENS)


def _factcheck_post_fn(base_url: str, timeout_seconds: int) -> PostFn:
    return _json_post_fn(base_url, timeout_seconds, _factcheck_json_schema(), "factcheck", FACTCHECK_MAX_TOKENS)


class LocalLlamaProvider:
    """Primary: prebuilt llama.cpp llama-server over its OpenAI-compatible API."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8080",
        model_name: str = PRIMARY_MODEL,
        timeout_seconds: int = 180,
        post_fn: PostFn | None = None,
        factcheck_post_fn: PostFn | None = None,
    ) -> None:
        self.base_url = base_url
        self.model_name = model_name
        self.timeout_seconds = timeout_seconds
        self._post_fn = post_fn or _brief_post_fn(base_url, timeout_seconds)
        self._factcheck_post_fn = (
            factcheck_post_fn or _factcheck_post_fn(base_url, timeout_seconds)
        )
        self.last_raw: str = ""
        self.last_error: str | None = None
        # Token usage of the last successful call (llama-server reports
        # {"prompt_tokens","completion_tokens"}; absent offline -> {}).
        self.last_usage: dict[str, Any] = {}

    def generate(
        self, cluster: Mapping[str, Any]
    ) -> tuple[dict[str, Any] | None, str, str | None]:
        return self.generate_with_messages(build_messages(cluster))

    def generate_with_messages(
        self, messages: list[dict[str, str]], temperature: float | None = None
    ) -> tuple[dict[str, Any] | None, str, str | None]:
        try:
            payload: dict[str, Any] = {"messages": messages}
            if temperature is not None:
                payload["temperature"] = temperature
            resp = self._post_fn(payload)
        except Exception as exc:  # noqa: BLE001 - transport failure -> fallback
            self.last_raw = ""
            self.last_error = f"llm transport: {type(exc).__name__}: {str(exc)[:200]}"
            return None, "", self.last_error
        try:
            content = resp["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            self.last_raw = ""
            self.last_error = f"llm bad response envelope: {exc}"
            return None, "", self.last_error
        if not isinstance(content, str):
            content = json.dumps(content)
        self.last_raw = content
        if isinstance(resp, dict):
            usage = resp.get("usage")
            self.last_usage = dict(usage) if isinstance(usage, dict) else {}
        brief, err = parse_brief_json(content)
        if err:
            self.last_error = err
            return None, content, err
        self.last_error = None
        return brief, content, None

    def generate_factcheck(
        self, messages: list[dict[str, str]]
    ) -> tuple[list[str] | None, str, str | None]:
        """Factcheck pass over an already-validated brief.

        Unlike ``generate_with_messages`` this is constrained by the
        factcheck JSON schema (``{"unsupported": [...]}``) -- *not* the brief
        schema -- and parses the model response into that separate shape.
        Returns ``(unsupported_list, raw_content, error)`` where
        ``unsupported_list`` is a list of strings (possibly empty). An
        empty list means the brief is fully supported; a non-empty list
        means the caller should reject the brief.
        """
        try:
            resp = self._factcheck_post_fn({"messages": messages})
        except Exception as exc:  # noqa: BLE001 - transport failure -> fail-open
            self.last_raw = ""
            self.last_error = f"factcheck transport: {type(exc).__name__}: {str(exc)[:200]}"
            return None, "", self.last_error
        try:
            content = resp["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            self.last_raw = ""
            self.last_error = f"factcheck bad response envelope: {exc}"
            return None, "", self.last_error
        if not isinstance(content, str):
            content = json.dumps(content)
        self.last_raw = content
        unsupported, err = parse_factcheck_json(content)
        if err:
            self.last_error = f"factcheck parse: {err}"
            return None, content, self.last_error
        self.last_error = None
        return unsupported, content, None


class CloudflareWorkersAIProvider:
    """Optional fallback: Cloudflare Workers AI free daily allocation.

    Enabled only when CF_ACCOUNT_ID + CF_API_TOKEN exist. On quota/HTTP
    error it returns None (never required, never raises for the chain).
    """

    def __init__(
        self,
        account_id: str | None = None,
        api_token: str | None = None,
        model: str = "@cf/qwen/qwen3-4b",
        timeout_seconds: int = 120,
        post_fn: PostFn | None = None,
    ) -> None:
        self.account_id = account_id or os.environ.get("CF_ACCOUNT_ID", "")
        self.api_token = api_token or os.environ.get("CF_API_TOKEN", "")
        self.model = model or os.environ.get("CF_AI_MODEL", "@cf/qwen/qwen3-4b")
        self.timeout_seconds = timeout_seconds
        self._post_fn = post_fn
        self.last_raw = ""
        self.last_error: str | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.account_id and self.api_token)

    def generate(
        self, cluster: Mapping[str, Any]
    ) -> tuple[dict[str, Any] | None, str, str | None]:
        if not self.enabled:
            return None, "", "workers-ai disabled (no CF_ACCOUNT_ID/CF_API_TOKEN)"
        return self.generate_with_messages(build_messages(cluster))

    def generate_with_messages(
        self, messages: list[dict[str, str]], temperature: float | None = None
    ) -> tuple[dict[str, Any] | None, str, str | None]:
        assert self.enabled
        try:
            if self._post_fn is not None:
                payload: dict[str, Any] = {"messages": messages}
                if temperature is not None:
                    payload["temperature"] = temperature
                resp = self._post_fn(payload)
                # Test seam: fake post_fn returns an OpenAI-style envelope
                # or raises. Reuse the same envelope parsing as local.
                try:
                    content = resp["choices"][0]["message"]["content"]
                except (KeyError, IndexError, TypeError):
                    content = json.dumps(resp)
            else:
                url = (
                    f"https://api.cloudflare.com/client/v4/accounts/"
                    f"{self.account_id}/ai/run/{self.model}"
                )
                payload = {"messages": messages}
                req = urllib.request.Request(
                    url,
                    json.dumps(payload).encode("utf-8"),
                    {
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {self.api_token}",
                    },
                )
                with urllib.request.urlopen(req, timeout=self.timeout_seconds) as r:  # noqa: S310
                    data = json.loads(r.read().decode("utf-8", errors="replace"))
                # Workers AI returns {"result": {"response": "..."}}.
                result = data.get("result", data)
                content = result.get("response", result) if isinstance(result, dict) else result
                if not isinstance(content, str):
                    content = json.dumps(content)
        except Exception as exc:  # noqa: BLE001 - quota/HTTP -> None, never fatal
            self.last_raw = ""
            self.last_error = f"workers-ai: {type(exc).__name__}: {str(exc)[:200]}"
            return None, "", self.last_error
        if not isinstance(content, str):
            content = json.dumps(content)
        self.last_raw = content
        brief, err = parse_brief_json(content)
        if err:
            self.last_error = f"workers-ai parse: {err}"
            return None, content, self.last_error
        self.last_error = None
        return brief, content, None


# ---------------------------------------------------------------------------
# Stories: AI brief -> story.schema.json, source card fallback
# ---------------------------------------------------------------------------

def _now_iso(now: datetime | None = None) -> str:
    moment = now or datetime.now(timezone.utc)
    return moment.isoformat().replace("+00:00", "Z")


def _cluster_times(cluster: Mapping[str, Any]) -> tuple[str, str]:
    members = [m for m in (cluster.get("members", []) or []) if isinstance(m, Mapping)]
    times = sorted(str(m.get("publishedAt") or "") for m in members if m.get("publishedAt"))
    first = str(cluster.get("firstSeen") or (times[0] if times else _now_iso()))
    last = str(cluster.get("lastSeen") or (times[-1] if times else first))
    return first, last


def _provenance_sources(cluster: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Full sources[] with original headlines/URLs/timestamps (never invented)."""
    if isinstance(cluster.get("sources"), list) and cluster["sources"]:
        out: list[dict[str, Any]] = []
        for s in cluster["sources"]:
            if not isinstance(s, Mapping):
                continue
            entry: dict[str, Any] = {
                "publisher": str(s.get("publisher") or ""),
                "headline": str(s.get("headline") or ""),
                "url": str(s.get("url") or ""),
            }
            if s.get("publishedAt"):
                entry["publishedAt"] = str(s["publishedAt"])
            if s.get("rightsMode"):
                entry["rightsMode"] = str(s["rightsMode"])
            if s.get("excerpt"):
                entry["excerpt"] = str(s["excerpt"])[:300]
            if entry["publisher"] and entry["headline"] and entry["url"]:
                out.append(entry)
        if out:
            return out
    # Fallback: derive from members.
    out = []
    for m in _members_for_story(cluster):
        entry = {
            "publisher": str(m.get("publisher") or ""),
            "headline": str(m.get("headline") or ""),
            "url": str(m.get("url") or ""),
        }
        if m.get("publishedAt"):
            entry["publishedAt"] = str(m["publishedAt"])
        if m.get("rightsMode"):
            entry["rightsMode"] = str(m["rightsMode"])
        excerpt = str(m.get("excerpt") or "")
        if excerpt:
            entry["excerpt"] = excerpt[:300]
        if entry["publisher"] and entry["headline"] and entry["url"]:
            out.append(entry)
    return out


def _members_for_story(cluster: Mapping[str, Any]) -> list[dict[str, Any]]:
    members = cluster.get("members", []) or []
    return [dict(m) for m in members if isinstance(m, Mapping)]


def _representative_member(
    cluster: Mapping[str, Any],
    sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    members = _members_for_story(cluster)
    if not members:
        return {}
    sources_by_id = sources_by_id or {}

    def _key(m: dict[str, Any]) -> tuple[int, str]:
        src = sources_by_id.get(str(m.get("sourceId") or ""), {})
        try:
            prio = int(src.get("priority", 50))
        except (TypeError, ValueError):
            prio = 50
        if str(m.get("rightsMode") or "") == "LINK_ONLY":
            prio -= 100
        return (prio, str(m.get("publishedAt") or ""))

    return max(members, key=_key)


class SourceCardProvider:
    """Deterministic fallback, always available. No fabricated summary:
    original headline, publisher, permitted excerpt, link."""

    def build_card(
        self,
        cluster: Mapping[str, Any],
        sources_by_id: Mapping[str, Mapping[str, Any]] | None = None,
        *,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        moment_iso = _now_iso(now)
        event_id = str(cluster.get("eventId") or "unknown")
        best = _representative_member(cluster, sources_by_id)
        headline = str(cluster.get("headline") or best.get("headline") or "Developing story")[:200]
        if len(headline) < 8:
            headline = (headline + " — developing story")[:200]
        # Permitted excerpt only (rights already enforced at ingest).
        excerpt: str | None = None
        for m in sorted(
            _members_for_story(cluster),
            key=lambda m: str(m.get("publishedAt") or ""),
            reverse=True,
        ):
            text = str(m.get("excerpt") or "").strip()
            if text:
                excerpt = text[:300]
                break
        first_seen, last_seen = _cluster_times(cluster)
        try:
            version = int(cluster.get("version", 1))
        except (TypeError, ValueError):
            version = 1
        tier = str(cluster.get("confidenceTier") or cluster.get("confidence") or "low").lower()
        if tier == "unverified":
            tier = "low"
        if tier not in ("high", "medium", "low"):
            tier = "low"
        locations = list(cluster.get("locations", []) or [])
        if not locations:
            locations = [{"country": "US"}]
        story: dict[str, Any] = {
            "apiVersion": 1,
            "id": event_id,
            "headline": headline,
            "category": str(cluster.get("category") or "local"),
            "publishedAt": first_seen,
            "updatedAt": last_seen,
            "generatedAt": moment_iso,
            "aiGenerated": False,
            "version": version,
            "revisions": [{"version": version, "updatedAt": moment_iso}],
            "confidenceTier": tier,
            "breaking": bool(cluster.get("breaking", False)),
            "locations": locations,
            "sources": _provenance_sources(cluster),
        }
        if excerpt:
            story["excerpt"] = excerpt
        return story


def build_ai_story(
    brief: Mapping[str, Any],
    cluster: Mapping[str, Any],
    *,
    model_name: str,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Validated brief + cluster provenance -> story.schema.json story."""
    moment_iso = _now_iso(now)
    event_id = str(cluster.get("eventId") or "")
    first_seen, last_seen = _cluster_times(cluster)
    try:
        version = int(cluster.get("version", 1))
    except (TypeError, ValueError):
        version = 1
    tier = str(cluster.get("confidenceTier") or cluster.get("confidence") or "low").lower()
    if tier == "unverified":
        tier = "low"
    if tier not in ("high", "medium", "low"):
        tier = "low"
    locations = list(brief.get("locations", []) or []) or list(cluster.get("locations", []) or [])
    if not locations:
        locations = [{"country": "US"}]
    return {
        "apiVersion": 1,
        "id": event_id,
        "headline": str(brief.get("headline") or "")[:200],
        "dek": str(brief.get("dek") or "")[:300],
        "body": str(brief.get("body") or "")[:6000],
        "category": str(brief.get("category") or cluster.get("category") or "local"),
        "publishedAt": first_seen,
        "updatedAt": last_seen,
        "generatedAt": moment_iso,  # pipeline overwrites; never trust the model
        "aiGenerated": True,
        "aiModel": model_name,  # pipeline overwrites; never trust the model
        "version": version,
        "revisions": [{"version": version, "updatedAt": moment_iso}],
        "confidenceTier": tier,
        "breaking": bool(cluster.get("breaking", False)),
        "locations": locations,
        "sources": _provenance_sources(cluster),
    }


# Retry temperature: the first try runs cool (0.2, factual) while the single
# retry runs warmer so it can escape the same failure basin (e.g. the same
# filler sentence at temp 0.2). The retry is still fully validated, so extra
# diversity never lowers quality -- a bad retry just falls back to a card.
RETRY_TEMPERATURE = 0.6


def try_brief_with_retry(
    provider: BriefProvider,
    cluster: Mapping[str, Any],
) -> tuple[dict[str, Any] | None, ValidationResult | None, str, str | None]:
    """Generate + validate with one regeneration on failure.

    Returns (brief|None, validation|None, raw_json, error). Rejections are
    returned (not raised) so the caller can log the reason and fall back.
    """
    brief, raw, err = provider.generate(cluster)
    if brief is None:
        reason = err or "empty model output"
        return None, None, raw, reason
    result = validate_brief(brief, cluster)
    if result.ok:
        return brief, result, raw, None
    # One regeneration with the failure reason + targeted hints appended,
    # at a warmer temperature so it does not repeat the same failure.
    reason = "; ".join(result.reasons)[:1500]
    if hasattr(provider, "generate_with_messages"):
        try:
            brief2, raw2, err2 = provider.generate_with_messages(  # type: ignore[attr-defined]
                build_retry_messages(cluster, raw, reason),
                temperature=RETRY_TEMPERATURE,
            )
        except TypeError:
            # Providers without a temperature knob (older fakes in tests).
            brief2, raw2, err2 = provider.generate_with_messages(  # type: ignore[attr-defined]
                build_retry_messages(cluster, raw, reason)
            )
        if brief2 is None:
            retry_detail = err2 or "retry transport failed"
            if not retry_detail.lower().startswith("retry failed"):
                retry_msg = f"retry failed: {retry_detail}"
            else:
                retry_msg = retry_detail
            combined_reasons = list(result.reasons) + [retry_msg]
            combined_result = ValidationResult(ok=False, reasons=combined_reasons)
            combined_error = f"{reason}; {retry_msg}" if reason else retry_msg
            return None, combined_result, raw2, combined_error
        result2 = validate_brief(brief2, cluster)
        if result2.ok:
            return brief2, result2, raw2, None
        return None, result2, raw2, "; ".join(result2.reasons)[:1500]
    return None, result, raw, reason
