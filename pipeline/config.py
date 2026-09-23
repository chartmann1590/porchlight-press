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
