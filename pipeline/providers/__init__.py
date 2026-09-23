"""SourceProvider protocol, RawItem, registry loading and provider dispatch."""
from __future__ import annotations

import inspect
import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Protocol


@dataclass
class RawItem:
    """One un-normalized discovery from a provider."""

    source_id: str
    publisher: str
    title: str
    url: str
    summary_html: str | None = None
    published_at: datetime | None = None
    language: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)


class SourceProvider(Protocol):
    def fetch(self, source: Mapping[str, Any]) -> list[RawItem]:
        """Fetch one registry source. Never raises StopRun-style errors;
        transport/parse failures raise ProviderError (ingest treats one
        failing source as non-fatal)."""
        ...


class ProviderError(Exception):
    """One source failed; the run continues with the rest."""

    def __init__(self, source_id: str, kind: str, detail: str):
        super().__init__(f"{source_id}: {kind}: {detail}")
        self.source_id = source_id
        self.kind = kind
        self.detail = detail


def provider_error_from_http(
    source_id: str, exc: Exception, *, rate_limit_detail: str | None = None
) -> ProviderError:
    """Map a transport failure to a ProviderError in one shared place.

    HTTP 429 becomes kind "rate-limited" (non-fatal, retried next run;
    get_bytes never retries it); everything else stays kind "http".
    """
    import httpx

    if isinstance(exc, httpx.HTTPStatusError) and (
        exc.response is not None and exc.response.status_code == 429
    ):
        return ProviderError(
            source_id,
            "rate-limited",
            rate_limit_detail
            or "provider asked for fewer requests; back off and retry next run",
        )
    return ProviderError(source_id, "http", str(exc)[:300])


def fetch_with_state(provider, source: Mapping[str, Any], http_state: dict):
    """Call fetch() with HTTP state where the provider accepts it.

    Single dispatch helper shared by ingest and health: providers that
    declare an ``http_state`` parameter (e.g. RSS conditional-GET state)
    receive it, the rest are called with just the source. Detected via
    inspect.signature so a real TypeError inside a provider is never
    swallowed the way a try/except-TypeError fallback would.
    """
    if "http_state" in inspect.signature(provider.fetch).parameters:
        return provider.fetch(source, http_state)
    return provider.fetch(source)


def load_sources(sources_dir: str | Path) -> list[dict[str, Any]]:
    """Load every registry entry below sources_dir (skips regions.json)."""
    out: list[dict[str, Any]] = []
    for path in sorted(Path(sources_dir).rglob("*.json")):
        if path.name == "regions.json":
            continue
        out.append(json.loads(path.read_text(encoding="utf-8")))
    return out


def get_provider(source_type: str, deps: Mapping[str, Any]) -> SourceProvider:
    """Dispatch registry `type` to a provider instance. `deps` carries the
    shared httpx client, config and contact string."""
    # Local imports keep `pipeline.providers` import-light for tests.
    from .gdelt import GdeltProvider
    from .json_api import JsonApiProvider
    from .nws_alerts import NwsAlertsProvider
    from .rss_atom import RssAtomProvider

    if source_type in ("rss", "atom"):
        return RssAtomProvider(deps["client"], deps["max_body_bytes"])
    if source_type == "gdelt":
        return GdeltProvider(deps["client"], deps["max_body_bytes"])
    if source_type == "nws-alerts":
        return NwsAlertsProvider(deps["client"], deps["max_body_bytes"])
    if source_type == "json-api":
        return JsonApiProvider(deps["client"], deps["max_body_bytes"])
    raise ValueError(f"unknown source type: {source_type}")
