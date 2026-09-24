"""Pipeline config: flags live in pipeline/config.yaml, never Remote Config."""
from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping

DEFAULTS: dict[str, Any] = {
    "staleDaysDefault": 7,
    "requestTimeoutSeconds": 15,
    "maxBodyBytes": 2_097_152,
    "userAgentContact": "me@charleshartman.com",
    "feedBaseUrl": "https://chartmann1590.github.io/porchlight-press/",
    "features": {
        "enableGdelt": True,
        "enableNwsAlerts": True,
        "aiMaxArticlesPerRun": 50,
    },
    "ai": {
        # MASTER_PLAN section 11 budget: 25-min wall-clock cap, ~50 briefs
        # per run with 4B. Always uses the 4B primary for quality (benchmark
        # 4B 5/8 vs 1.7B 1/30 at run 35983811629); throughput is not the goal
        # because the time-budgeted stage publishes top-ranked stories first
        # and carries the rest to the next run (4 runs/day).
        "primaryModel": "Qwen3-4B-Q4_K_M",
        "fallbackModel": "Qwen3-1.7B-Q8_0",
        "overflowThreshold": 9999,
        "wallClockMinutes": 25,
        "llamaUrl": "http://127.0.0.1:8080",
        # Optional second AI pass (same model). Off by default; enable only
        # when the time budget allows.
        "enableFactCheck": False,
    },
    # Phase 4: static publishing caps (section caps from the plan:
    # MAX_LOCAL/STATE/NATIONAL/WORLD_ARTICLES, plus regional which the plan
    # folds into the locality ladder; regional defaults to its own cap).
    "publish": {
        "maxStoriesPerEdition": 30,
        "maxLocalArticles": 20,
        "maxRegionalArticles": 10,
        "maxStateArticles": 10,
        "maxNationalArticles": 10,
        "maxWorldArticles": 10,
    },
    "images": {
        # Commons search results considered per query; first confident
        # title match wins. Higher values cost more API calls.
        "commonsResultsPerQuery": 3,
    },
}


def _deep_merge(base: dict[str, Any], override: Mapping) -> dict[str, Any]:
    merged = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    root = Path(__file__).resolve().parent
    cfg_path = Path(path) if path else root / "config.yaml"
    if not cfg_path.exists():
        return _deep_merge({}, DEFAULTS)
    import yaml

    data = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    return _deep_merge(DEFAULTS, data)
