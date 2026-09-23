"""Rights-mode matrix (Phase 1).

The spec names the modes; this module defines their behaviour, enforced at
ingest time by pipeline/normalize.py:

| Mode               | Ingest     | Excerpt shown | Excerpt fed to AI | Image reuse | Listed |
|--------------------|------------|---------------|-------------------|-------------|--------|
| PUBLIC_DOMAIN      | full text  | yes           | yes               | yes         | yes    |
| OPEN_LICENSE       | full text  | yes           | yes (attrib)      | yes+attrib  | yes    |
| RSS_EXCERPT_ALLOWED| summary    | yes (<=300ch) | yes               | no          | yes    |
| METADATA_ONLY      | title/time | no            | headline only     | no          | yes    |
| LINK_ONLY          | title+url  | no            | never brief basis | no          | yes    |
| BLOCKED            | dropped    | -             | -                 | -           | no     |

GDELT-discovered items default to METADATA_ONLY unless the domain matches a
registry entry with a more permissive mode.
"""
from __future__ import annotations

RIGHTS_MODES = (
    "PUBLIC_DOMAIN",
    "OPEN_LICENSE",
    "RSS_EXCERPT_ALLOWED",
    "METADATA_ONLY",
    "LINK_ONLY",
    "BLOCKED",
)

# Max excerpt characters the pipeline will keep per mode (0 = none).
MAX_EXCERPT_CHARS = {
    "PUBLIC_DOMAIN": 2000,
    "OPEN_LICENSE": 2000,
    "RSS_EXCERPT_ALLOWED": 300,
    "METADATA_ONLY": 0,
    "LINK_ONLY": 0,
    "BLOCKED": 0,
}


def excerpt_allowed(rights_mode: str) -> bool:
    return MAX_EXCERPT_CHARS.get(rights_mode, 0) > 0


def excerpt_limit(rights_mode: str) -> int:
    return MAX_EXCERPT_CHARS.get(rights_mode, 0)


def may_feed_ai_text(rights_mode: str) -> bool:
    """Whether body/excerpt text may be used as AI brief input.

    METADATA_ONLY contributes its headline only; LINK_ONLY items may join a
    cluster but must never be the basis of a brief (enforced in Phase 2/3).
    """
    return rights_mode in ("PUBLIC_DOMAIN", "OPEN_LICENSE", "RSS_EXCERPT_ALLOWED", "METADATA_ONLY")


def may_reuse_image(rights_mode: str) -> bool:
    return rights_mode in ("PUBLIC_DOMAIN", "OPEN_LICENSE")


def is_ingestible(rights_mode: str) -> bool:
    return rights_mode in RIGHTS_MODES and rights_mode != "BLOCKED"
