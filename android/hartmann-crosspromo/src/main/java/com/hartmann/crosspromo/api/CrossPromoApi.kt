package com.hartmann.crosspromo.api

import com.hartmann.crosspromo.model.PromoEvent
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Resilient HTTP client for Hartmann Cross-Promotion API.
 * Uses OkHttp and kotlinx.serialization with strict unknown-property tolerance.
 */
class CrossPromoApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Fetches ranked recommendations from backend.
     */
    suspend fun getRecommendations(
        baseUrl: String,
        sourcePackage: String,
        placement: String,
        limit: Int = 3,
        sessionId: String? = null,
        exclude: List<String>? = null,
        locale: String? = null
    ): Result<PromoResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trimEnd('/')
            val fullUrl = "$cleanBase/api/v1/recommendations".toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL: $baseUrl"))

            val urlBuilder = fullUrl.newBuilder()
                .addQueryParameter("sourcePackage", sourcePackage)
                .addQueryParameter("placement", placement)
                .addQueryParameter("limit", limit.toString())

            sessionId?.let { urlBuilder.addQueryParameter("sessionId", it) }
            locale?.let { urlBuilder.addQueryParameter("locale", it) }
            if (!exclude.isNullOrEmpty()) {
                urlBuilder.addQueryParameter("exclude", exclude.joinToString(","))
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .header("User-Agent", "HartmannCrossPromo-Android/1.0.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("Backend returned HTTP ${response.code}")
                    )
                }

                val bodyString = response.body?.string()
                    ?: return@withContext Result.failure(RuntimeException("Empty response body"))

                val parsed = json.decodeFromString<PromoResponse>(bodyString)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dispatches an analytics event asynchronously to backend.
     */
    suspend fun sendEvent(
        baseUrl: String,
        event: PromoEvent
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trimEnd('/')
            val endpoint = "$cleanBase/api/v1/events"
            val payload = json.encodeToString(PromoEvent.serializer(), event)

            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("User-Agent", "HartmannCrossPromo-Android/1.0.0")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(RuntimeException("Event dispatch failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
