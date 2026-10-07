#!/usr/bin/env python3
"""Copy a normalized-items fixture with its timestamps shifted so the newest item is an hour old.

The pipeline measures item age against the real clock (dedupe window, cluster
retention), so a fixture with fixed dates silently stops producing clusters a few
days after it was written. CI smoke steps run the fixture through this first.

Usage: python scripts/shift_fixture_to_now.py IN.json OUT.json
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta, timezone


def _parse(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def _iso(dt: datetime) -> str:
    return dt.isoformat().replace("+00:00", "Z")


def shift(data: dict, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    items = data.get("items") or []
    stamps = [_parse(i["publishedAt"]) for i in items if i.get("publishedAt")]
    if not stamps:
        return data
    delta = (now - timedelta(hours=1)) - max(stamps)
    for item in items:
        if item.get("publishedAt"):
            item["publishedAt"] = _iso(_parse(item["publishedAt"]) + delta)
    if data.get("generatedAt"):
        data["generatedAt"] = _iso(_parse(data["generatedAt"]) + delta)
    return data


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    src, dst = argv
    with open(src, encoding="utf-8") as fh:
        data = json.load(fh)
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(shift(data), fh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
