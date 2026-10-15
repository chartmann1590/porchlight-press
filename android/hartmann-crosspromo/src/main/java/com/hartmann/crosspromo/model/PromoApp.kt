package com.hartmann.crosspromo.model

import kotlinx.serialization.Serializable

/**
 * Normalized data model representing a recommended Hartmann Studios application.
 * All properties are designed with forward-compatibility and nullable fallbacks.
 */
@Serializable
data class PromoApp(
    val packageName: String,
    val name: String,
    val iconUrl: String? = null,
    val shortDescription: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val installText: String? = null,
    val storeUrl: String,
    val selectionType: String = "popular", // "popular" | "exploration" | "boost"
    val rankPosition: Int = 1
)
