package com.hartmann.crosspromo

import android.content.Context
import android.content.SharedPreferences
import com.hartmann.crosspromo.model.PromoApp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HartmannCrossPromoTest {

    /** Tracks which pref files exist so the two SharedPreferences stores are distinct. */
    private val prefStores = mutableMapOf<String, InMemoryPrefs>()

    private fun fakeContext(): Context {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.charleshartman.porchlightpress"
        every { context.getSharedPreferences(any(), any()) } answers { arg ->
            val name = firstArg<String>()
            prefStores.getOrPut(name) { InMemoryPrefs() }
        }
        return context
    }

    class InMemoryPrefs : SharedPreferences {
        val store = mutableMapOf<String, Any>()
        private val editor = object : SharedPreferences.Editor {
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
        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (store[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = editor
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun app(pkg: String = "com.auracast.weather") = PromoApp(
        packageName = pkg,
        name = "AuraCast Weather",
        storeUrl = "https://play.google.com/store/apps/details?id=$pkg"
    )

    /**
     * Hermetic initialization: points the backend adapter at an unroutable
     * loopback port so no test traffic reaches the production worker, and
     * skips install-referrer reflection entirely.
     */
    private fun initializeHermetic() {
        HartmannCrossPromo.resetForTesting()
        HartmannCrossPromo.initialize(
            fakeContext(),
            apiBaseUrl = "http://127.0.0.1:1",
            customAnalytics = null,
            enableInstallReferrerCheck = false
        )
    }

    @Test
    fun `user opt-out toggles enabled and persists`() {
        initializeHermetic()
        assertTrue(HartmannCrossPromo.enabled)

        HartmannCrossPromo.setUserOptOut(true)
        assertFalse(HartmannCrossPromo.enabled)
        assertTrue(HartmannCrossPromo.isUserOptedOut())

        HartmannCrossPromo.setUserOptOut(false)
        assertTrue(HartmannCrossPromo.enabled)
        assertFalse(HartmannCrossPromo.isUserOptedOut())
    }

    @Test
    fun `getRecommendations yields nothing after opt-out`() = runTest {
        initializeHermetic()
        HartmannCrossPromo.setUserOptOut(true)

        val emitted = HartmannCrossPromo.getRecommendations(placement = "settings", limit = 3).firstOrNull()
        assertNull(emitted)
    }

    @Test
    fun `impressions dedupe per placement, not globally`() {
        initializeHermetic()
        HartmannCrossPromo.onAppImpression(app(), "home")
        HartmannCrossPromo.onAppImpression(app(), "home")
        HartmannCrossPromo.onAppImpression(app(), "settings")

        val cache = HartmannCrossPromo.cacheForTesting()
        // Same app on a different placement must still have been recorded as shown.
        assertEquals(listOf("com.auracast.weather"), cache!!.getShownInSession("settings"))
        assertEquals(listOf("com.auracast.weather"), cache.getShownInSession("home"))
    }
}
