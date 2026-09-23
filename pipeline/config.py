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
        # per run with 4B; >50 queued switches the whole run to 1.7B.
        "primaryModel": "Qwen3-4B-Q4_K_M",
        "fallbackModel": "Qwen3-1.7B-Q8_0",
        "overflowThreshold": 50,
        "wallClockMinutes": 25,
        "llamaUrl": "http://127.0.0.1:8080",
        # Optional second AI pass (same model). Off by default; enable only
        # when the time budget allows.
        "enableFactCheck": False,
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
