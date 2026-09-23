"""Source health: dead/invalid/stale/redirected feeds, registry problems.

One failing source never fails the run; failures are reported to the caller,
and the ingest CLI can append them to a summary file (--summary-file).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping
from urllib.parse import urlparse

from .providers import ProviderError, get_provider
from .rights import RIGHTS_MODES


@dataclass
class SourceHealth:
    source_id: str
    ok: bool
    issues: list[str] = field(default_factory=list)
    item_count: int = 0


def check_source(
    source: Mapping[str, Any],
    deps: Mapping[str, Any],
    *,
    stale_days: int = 7,
    now: datetime | None = None,
) -> SourceHealth:
    sid = str(source["id"])
    moment = now or datetime.now(timezone.utc)
    health = SourceHealth(source_id=sid, ok=True)

    feed_url = str(source.get("feedUrl") or source.get("apiUrl") or "")
    if feed_url:
        try:
            parsed = urlparse(feed_url)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                health.issues.append(f"bad-url: {feed_url[:120]}")
                health.ok = False
        except ValueError:
            health.issues.append("bad-url: unparseable")
            health.ok = False

    try:
        provider = get_provider(str(source["type"]), deps)
        http_state: dict[str, Any] = {}
        try:
            items = provider.fetch(source, http_state)
        except TypeError:
            items = provider.fetch(source)
    except ProviderError as exc:
        health.issues.append(f"{exc.kind}: {exc.detail[:200]}")
        health.ok = False
        return health
    except Exception as exc:  # noqa: BLE001 - never fatal
        health.issues.append(f"unexpected: {type(exc).__name__}: {str(exc)[:200]}")
        health.ok = False
        return health

    health.item_count = len(items)
    slot = http_state.get(sid, {})
    if slot.get("permanentRedirect") and slot.get("finalUrl"):
        health.issues.append(
            f"permanent-redirect: configured feed now resolves to {slot['finalUrl'][:160]}"
        )
    if not items:
        health.issues.append("empty: feed returned no usable items")
        return health

    dated = [i for i in items if i.published_at is not None]
    if not dated:
        health.issues.append("no-dated-items: nothing to judge staleness by")
        return health
    newest = max(i.published_at for i in dated)
    assert newest is not None
    if newest.tzinfo is None:
        newest = newest.replace(tzinfo=timezone.utc)
    if (moment - newest) > timedelta(days=stale_days):
        health.issues.append(
            f"stale: newest item {newest.date().isoformat()} older than {stale_days}d"
        )
    return health


def check_registry(sources: list[Mapping[str, Any]]) -> list[str]:
    """Registry-level problems: duplicate IDs, unknown rights modes/licenses."""
    problems: list[str] = []
    seen: dict[str, int] = {}
    for src in sources:
        sid = str(src.get("id", ""))
        seen[sid] = seen.get(sid, 0) + 1
        mode = str(src.get("rightsMode", ""))
        if mode not in RIGHTS_MODES:
            problems.append(f"{sid}: unknown rightsMode '{mode}'")
        if mode == "OPEN_LICENSE" and not src.get("license"):
            problems.append(f"{sid}: OPEN_LICENSE needs a license string")
    for sid, count in seen.items():
        if count > 1:
            problems.append(f"duplicate source id '{sid}' x{count}")
    return problems
