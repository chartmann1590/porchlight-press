package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.FetchOutcome
import com.charleshartman.porchlightpress.data.weather.WeatherFetcher

/** Fake HTTP for weather unit tests: records conditional headers. */
class FakeWeatherFetcher(
    var handler: (url: String, etag: String?, lastModified: String?) -> FetchOutcome =
        { _, _, _ -> FetchOutcome.Error(500, "no stub") },
) : WeatherFetcher {
    data class Call(val url: String, val etag: String?, val lastModified: String?)
    val calls = mutableListOf<Call>()

    override suspend fun get(url: String, etag: String?, lastModified: String?): FetchOutcome {
        calls.add(Call(url, etag, lastModified))
        return handler(url, etag, lastModified)
    }

    fun callsTo(fragment: String): List<Call> = calls.filter { fragment in it.url }
}

fun testResource(name: String): String =
    checkNotNull(FakeWeatherFetcher::class.java.classLoader?.getResourceAsStream("weather/$name")) {
        "missing test resource weather/$name"
    }.bufferedReader().readText()

fun fresh(body: String, etag: String? = null, expiresInMs: Long = 3_600_000L): FetchOutcome.Fresh =
    FetchOutcome.Fresh(
        body = body,
        etag = etag,
        lastModified = null,
        expiresAt = System.currentTimeMillis() + expiresInMs,
    )
