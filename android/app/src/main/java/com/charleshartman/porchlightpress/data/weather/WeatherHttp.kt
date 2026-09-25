package com.charleshartman.porchlightpress.data.weather

import com.charleshartman.porchlightpress.data.remote.NetworkModule
import java.io.IOException
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Conditional HTTP fetcher for weather providers. Sends
 * If-None-Match/If-Modified-Since when the Room cache holds validators,
 * honors Expires/Cache-Control from the response, and bypasses the OkHttp
 * disk cache (Room is the source of truth, per-bucket with provider TTLs).
 */
sealed interface FetchOutcome {
    data class Fresh(
        val body: String,
        val etag: String?,
        val lastModified: String?,
        val expiresAt: Long,
    ) : FetchOutcome

    data object NotModified : FetchOutcome
    data class Error(val code: Int, val message: String) : FetchOutcome
}

interface WeatherFetcher {
    suspend fun get(url: String, etag: String?, lastModified: String?): FetchOutcome
}

class OkHttpWeatherFetcher(
    private val client: OkHttpClient,
    private val accept: String = "application/json",
) : WeatherFetcher {
    override suspend fun get(url: String, etag: String?, lastModified: String?): FetchOutcome =
        // OkHttp execute() blocks: never run it on the caller's thread
        // (ViewModels collect on Main; StrictMode would kill the call).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkModule.userAgent())
                .header("Accept", accept)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .apply {
                    if (!etag.isNullOrBlank()) header("If-None-Match", etag)
                    if (!lastModified.isNullOrBlank()) header("If-Modified-Since", lastModified)
                }
                .build()
            val resp: Response = try {
                client.newCall(req).execute()
            } catch (e: IOException) {
                throw e
            }
            resp.use {
                when (it.code) {
                    304 -> FetchOutcome.NotModified
                    in 200..299 -> {
                        val body = it.body?.string() ?: ""
                        val headers = it.headers
                        val now = System.currentTimeMillis()
                        FetchOutcome.Fresh(
                            body = body,
                            etag = headers["ETag"],
                            lastModified = headers["Last-Modified"],
                            expiresAt = parseExpiresAt(headers, now, defaultTtlMs(url)),
                        )
                    }
                    else -> FetchOutcome.Error(it.code, "HTTP ${it.code} for $url")
                }
            }
        }

    companion object {
        /** Default TTL when the response carries no cache headers. */
        fun defaultTtlMs(url: String): Long = when {
            "/alerts" in url -> 5L * 60L * 1000L
            else -> 30L * 60L * 1000L
        }

        fun parseExpiresAt(headers: okhttp3.Headers, now: Long, defaultTtlMs: Long): Long {
            // Cache-Control: max-age wins.
            val cc = runCatching { CacheControl.parse(headers) }.getOrNull()
            val maxAge = cc?.maxAgeSeconds ?: -1
            if (maxAge >= 0) return now + maxAge * 1000L
            // Then the Expires date (RFC 1123, e.g. "Wed, 24 Sep 2026 12:00:00 GMT").
            val expires = headers["Expires"]?.let { raw ->
                runCatching {
                    java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()
                }.getOrNull()
            }
            if (expires != null && expires > now) return expires
            return now + defaultTtlMs
        }
    }
}
