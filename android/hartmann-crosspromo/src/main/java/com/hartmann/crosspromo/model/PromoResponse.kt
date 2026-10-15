package com.hartmann.crosspromo.model

import kotlinx.serialization.Serializable

/**
 * Top-level response returned by Hartmann Studios cross-promotion API.
 */
@Serializable
data class PromoResponse(
    val version: Int = 1,
    val requestId: String = "",
    val generatedAt: String = "",
    val expiresAt: String = "",
    val apps: List<PromoApp> = emptyList()
)
