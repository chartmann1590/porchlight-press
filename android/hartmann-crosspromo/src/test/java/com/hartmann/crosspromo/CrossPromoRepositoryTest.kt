package com.hartmann.crosspromo

import android.content.Context
import android.content.SharedPreferences
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.model.PromoResponse
import com.hartmann.crosspromo.repository.CrossPromoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the stale-while-revalidate repository against a real OkHttp
 * stack (MockWebServer) and a SharedPreferences fake for PromoCache.
 */
class CrossPromoRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: PromoCache
    private lateinit var repository: CrossPromoRepository

    /** Controlled clock so tests can age caches past their TTL. */
    private var now = 1_000_000L

    private val sourcePackage = "com.charleshartman.porchlightpress"

    @Before
    fun setUp() {
        now = 1_000_000L
        server = MockWebServer()
        server.start()
        cache = PromoCache(fakeContext()) { now }
        repository = CrossPromoRepository(CrossPromoApi(), cache, server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** In-memory SharedPreferences-backed Context fake (plain JVM, no Robolectric). */
    private fun fakeContext(): Context {
        val store = mutableMapOf<String, Any>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) =
                apply { if (value == null) store.remove(key) else store[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { if (values == null) store.remove(key) else store[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { store[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { store[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { store[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { store[key!!] = value }
            override fun remove(key: String?) = apply { store.remove(key) }
            override fun clear() = apply { store.clear() }
            override fun commit() = true
            override fun apply() {}
        }
        val prefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = store
            override fun getString(key: String?, defValue: String?): String? =
                store[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                @Suppress("UNCHECKED_CAST")
                (store[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                store[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = store.containsKey(key)
            override fun edit(): SharedPreferences.Editor = editor
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    private fun responseBody(vararg packages: String): String {
        val apps = packages.joinToString(",") { pkg ->
            """
            {
              "packageName": "$pkg",
              "name": "App $pkg",
              "iconUrl": null,
              "shortDescription": "Short description",
              "rating": 4.5,
              "ratingCount": 100,
              "installText": "10K+",
              "storeUrl": "https://play.google.com/store/apps/details?id=$pkg",
              "selectionType": "popular",
              "rankPosition": 1
            }
            """.trimIndent()
        }
        return """
            {
              "version": 1,
              "requestId": "req-test",
              "generatedAt": "2026-09-28T12:00:00Z",
              "expiresAt": "2026-09-28T16:00:00Z",
              "apps": [$apps]
            }
        """.trimIndent()
    }

    private val seenRequests = java.util.concurrent.atomic.AtomicInteger(0)

    /** Drains only the requests that arrived since the previous call. */
    private fun recordedExclusions(): List<List<String>> {
        val recorded = mutableListOf<List<String>>()
        while (seenRequests.get() < server.requestCount) {
            val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                ?: break // No further request arrived; avoid blocking forever.
            seenRequests.incrementAndGet()
            val exclude = request.requestUrl?.queryParameter("exclude")
            recorded.add(exclude?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
        }
        return recorded
    }

    @Test
    fun `emits network result and serves fresh cache without refetching`() = runTest {
        server.enqueue(MockResponse().setBody(responseBody("com.auracast.weather", "com.charles.nutrisnap")))

        val first = repository.getRecommendations(sourcePackage, "settings", 2).first()
        assertEquals(2, first.size)

        // Second load hits the fresh cache: no new request may be enqueued.
        val second = repository.getRecommendations(sourcePackage, "settings", 2).first()
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `stale cache is served immediately and refreshed from the network`() = runTest {
        // Cache a response (server TTL: 4 hours per the fixture timestamps).
        server.enqueue(MockResponse().setBody(responseBody("com.auracast.weather")))
        val initial = repository.getRecommendations(sourcePackage, "settings", 1).toList()
        assertEquals(1, initial.size)

        // Age past the 4-hour server TTL, then collect the full SWR cycle:
        // cached apps emit instantly, then the network refresh appends.
        now += 5 * 60 * 60 * 1000L
        server.enqueue(MockResponse().setBody(responseBody("com.chartmann.knightfall")))
        val emissions = repository.getRecommendations(sourcePackage, "settings", 1).toList()
        assertEquals(2, emissions.size)
        assertEquals("Stale cache must be re-emitted instantly", "com.auracast.weather", emissions[0][0].packageName)
        assertEquals("Background revalidate must replace the data", "com.chartmann.knightfall", emissions[1][0].packageName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `shown-in-session exclusions are scoped per placement`() = runTest {
        // Home shows and impressions-mark one app...
        server.enqueue(MockResponse().setBody(responseBody("com.auracast.weather")))
        val homeApps = repository.getRecommendations(sourcePackage, "home", 1).first()
        assertEquals(1, homeApps.size)
        cache.markShownInSession("home", homeApps[0].packageName)

        // ...but a settings request must NOT exclude that package.
        server.enqueue(MockResponse().setBody(responseBody("com.auracast.weather")))
        repository.getRecommendations(sourcePackage, "settings", 1).first()

        val exclusions = recordedExclusions()
        assertEquals(2, exclusions.size)
        assertTrue(
            "settings request must not carry home's shown packages, got ${exclusions[1]}",
            exclusions[1].isEmpty()
        )

        // Mark a package for settings; after the cache goes stale, the next
        // settings fetch DOES carry the exclusion. Collect fully so the
        // background revalidation actually runs.
        cache.markShownInSession("settings", "com.chartmann.knightfall")
        now += 5 * 60 * 60 * 1000L
        server.enqueue(MockResponse().setBody(responseBody("com.charles.nutrisnap")))
        repository.getRecommendations(sourcePackage, "settings", 1).toList()

        // Only the new request is drained this time (delta-based helper).
        val exclusionsAfter = recordedExclusions()
        assertEquals(1, exclusionsAfter.size)
        assertEquals(
            "settings' own shown packages must be excluded on its next fetch",
            listOf("com.chartmann.knightfall"),
            exclusionsAfter[0]
        )
    }

    @Test
    fun `exclusion list is capped so small catalogs are never excluded into silence`() = runTest {
        for (i in 1..10) {
            cache.markShownInSession("home", "com.hartmann.app$i")
        }

        server.enqueue(MockResponse().setBody(responseBody("com.auracast.weather")))
        repository.getRecommendations(sourcePackage, "home", 1).first()

        val exclusions = recordedExclusions()
        assertEquals(1, exclusions.size)
        assertTrue(
            "Expected capped exclude list (<=2), got ${exclusions[0].size}: ${exclusions[0]}",
            exclusions[0].size <= 2
        )
    }

    @Test
    fun `fails silently and emits empty list when network errors with no cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val emitted = repository.getRecommendations(sourcePackage, "settings", 2).first()
        assertEquals(emptyList<PromoApp>(), emitted)
    }

    @Test
    fun `emits empty list rather than crashing on malformed response body`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))

        val emitted = repository.getRecommendations(sourcePackage, "settings", 2).first()
        assertEquals(emptyList<PromoApp>(), emitted)
    }

    @Test
    fun `cache honors server-provided TTL over the built-in default`() {
        // Controlled clock: entry written at t0.
        var now = 1_000_000L
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val clockedCache = PromoCache(fakeContext(), json) { now }
        // generatedAt -> expiresAt = 1 minute TTL (far below the 4h default)
        val response = json.decodeFromString<PromoResponse>(
            """
            {
              "version": 1,
              "requestId": "r1",
              "generatedAt": "2026-09-28T12:00:00Z",
              "expiresAt": "2026-09-28T12:01:00Z",
              "apps": [{
                "packageName": "com.auracast.weather",
                "name": "AuraCast Weather",
                "storeUrl": "https://play.google.com/store/apps/details?id=com.auracast.weather"
              }]
            }
            """.trimIndent()
        )
        clockedCache.putCached(sourcePackage, "settings", response)

        // 30 seconds later: within the 1-minute server TTL, not stale.
        now += 30_000L
        val fresh = clockedCache.getCached(sourcePackage, "settings")
        assertNotNull(fresh)
        assertFalse("Within server TTL must be fresh", fresh!!.isStale)

        // 2 minutes later: past the 1-minute server TTL but well within the
        // 4-hour default — proves the server TTL wins.
        now += 90_000L
        val stale = clockedCache.getCached(sourcePackage, "settings")
        assertNotNull(stale)
        assertTrue("Past server TTL must be stale even under 4h", stale!!.isStale)
    }

    @Test
    fun `promo response deserializes with unknown server fields`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val response = json.decodeFromString<PromoResponse>(
            """
            {
              "version": 1,
              "requestId": "r1",
              "generatedAt": "2026-09-28T12:00:00Z",
              "expiresAt": "2026-09-28T16:00:00Z",
              "future_field": true,
              "apps": [{
                "packageName": "com.auracast.weather",
                "name": "AuraCast Weather",
                "storeUrl": "https://play.google.com/store/apps/details?id=com.auracast.weather",
                "future_app_field": 42
              }]
            }
            """.trimIndent()
        )
        assertEquals(1, response.apps.size)
        assertEquals("com.auracast.weather", response.apps[0].packageName)
        assertEquals("popular", response.apps[0].selectionType)
        assertFalse(response.apps[0].packageName.isBlank())
    }
}
