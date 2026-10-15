package com.hartmann.crosspromo.repository

import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.model.PromoApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * Repository orchestrating stale-while-revalidate caching and backend discovery.
 */
class CrossPromoRepository(
    private val api: CrossPromoApi,
    private val cache: PromoCache,
    private val baseUrl: String
) {

    /**
     * Streams recommendations using stale-while-revalidate strategy:
     * 1. Emits cached apps immediately if present
     * 2. In background, requests fresh recommendations
     * 3. Emits fresh apps and persists to cache upon success
     * 4. Fails silently on error without disrupting the host app
     */
    fun getRecommendations(
        sourcePackage: String,
        placement: String = "default",
        limit: Int = 3
    ): Flow<List<PromoApp>> = flow {
        // 1. Read cached state
        val cached = cache.getCached(sourcePackage, placement)
        if (cached != null && cached.response.apps.isNotEmpty()) {
            emit(cached.response.apps)
            if (!cached.isStale) {
                // Fresh cache is sufficient; return early
                return@flow
            }
        }

        // 2. Fetch fresh recommendations from backend. Exclusions are scoped
        // to this placement and capped so a small catalog can never be
        // excluded into emptiness (which would silence promos for the rest
        // of the session); the backend's rotation handles repetition.
        val shownPackages = cache.getShownInSession(placement)
        val currentLocale = Locale.getDefault().toLanguageTag()

        val networkResult = api.getRecommendations(
            baseUrl = baseUrl,
            sourcePackage = sourcePackage,
            placement = placement,
            limit = limit,
            sessionId = cache.sessionId,
            exclude = shownPackages.ifEmpty { null },
            locale = currentLocale
        )

        networkResult.onSuccess { response ->
            if (response.apps.isNotEmpty()) {
                cache.putCached(sourcePackage, placement, response)
                emit(response.apps)
            } else if (cached == null) {
                emit(emptyList())
            }
        }.onFailure {
            // If cache was already emitted, keep it. Otherwise emit empty list.
            if (cached == null) {
                emit(emptyList())
            }
        }
    }
}
