package com.hartmann.crosspromo.model

import kotlinx.serialization.Serializable

/**
 * Analytics event payload dispatched to backend and/or Firebase.
 * Designed with strict privacy: no advertising ID, no IMEI, no personal data.
 */
@Serializable
data class PromoEvent(
    val event: String, // "promo_impression" | "promo_click" | "crosspromo_install"
    val sourcePackage: String,
    val targetPackage: String,
    val placement: String,
    val rankPosition: Int? = null,
    val selectionType: String? = null,
    val sessionId: String? = null,
    val recommendationRequestId: String? = null,
    val sdkVersion: String = "1.0.0",
    val timestamp: Long = System.currentTimeMillis()
)
