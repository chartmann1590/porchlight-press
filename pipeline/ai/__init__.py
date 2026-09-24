"""AI newsroom package (Phase 3): validated briefs with source-card fallback."""
from .providers import (
    FALLBACK_MODEL,
    PRIMARY_MODEL,
    CloudflareWorkersAIProvider,
    LocalLlamaProvider,
    SourceCardProvider,
    build_ai_story,
    try_brief_with_retry,
)
from .prompts import (
    build_messages,
    build_retry_messages,
    build_user_message,
    build_verbatim_repair_messages,
)
from .validate import (
    ValidationResult,
    parse_brief_json,
    parse_factcheck_json,
    publication_outcome_reasons,
    source_text_for_cluster,
    validate_brief,
)

__all__ = [
    "PRIMARY_MODEL",
    "FALLBACK_MODEL",
    "LocalLlamaProvider",
    "CloudflareWorkersAIProvider",
    "SourceCardProvider",
    "build_ai_story",
    "try_brief_with_retry",
    "build_messages",
    "build_retry_messages",
    "build_user_message",
    "build_verbatim_repair_messages",
    "ValidationResult",
    "parse_brief_json",
    "parse_factcheck_json",
    "publication_outcome_reasons",
    "source_text_for_cluster",
    "validate_brief",
]
