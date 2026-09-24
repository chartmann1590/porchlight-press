package com.charleshartman.porchlightpress.domain

/**
 * Repository results carry Stale/Offline/Error state for the UI
 * instead of throwing (Phase 5 spec).
 */
sealed interface FeedResult<out T> {
    data class Ok<T>(val value: T, val stale: Boolean = false) : FeedResult<T>
    data class Offline<T>(val cached: T? = null) : FeedResult<T>
    data class UpdateRequired(val message: String) : FeedResult<Nothing>
    data class Error(val message: String, val cached: Any? = null) : FeedResult<Nothing>
}
