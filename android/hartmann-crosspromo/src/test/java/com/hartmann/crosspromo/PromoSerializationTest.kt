package com.hartmann.crosspromo

import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.model.PromoEvent
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PromoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun testPromoResponseDeserializationWithUnknownKeys() {
        val payload = """
            {
                "version": 1,
                "requestId": "test-req-123",
                "generatedAt": "2026-09-25T12:00:00Z",
                "expiresAt": "2026-09-25T16:00:00Z",
                "future_unknown_server_field": "some_value",
                "apps": [
                    {
                        "packageName": "com.auracast.weather",
                        "name": "AuraCast Weather",
                        "iconUrl": "https://example.com/icon.png",
                        "shortDescription": "Weather app",
                        "rating": 4.8,
                        "ratingCount": 1500,
                        "installText": "10K+",
                        "storeUrl": "https://play.google.com/store/apps/details?id=com.auracast.weather",
                        "selectionType": "popular",
                        "rankPosition": 1,
                        "future_app_field": 42
                    }
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<PromoResponse>(payload)
        assertEquals(1, response.version)
        assertEquals("test-req-123", response.requestId)
        assertEquals(1, response.apps.size)

        val app = response.apps[0]
        assertEquals("com.auracast.weather", app.packageName)
        assertEquals("AuraCast Weather", app.name)
        assertEquals(4.8, app.rating!!, 0.01)
        assertEquals("popular", app.selectionType)
    }

    @Test
    fun testPromoEventSerialization() {
        val event = PromoEvent(
            event = "promo_click",
            sourcePackage = "com.charleshartman.porchlightpress",
            targetPackage = "com.auracast.weather",
            placement = "settings",
            rankPosition = 1,
            selectionType = "popular",
            sessionId = "sess-123"
        )

        val encoded = json.encodeToString(PromoEvent.serializer(), event)
        assertNotNull(encoded)

        val decoded = json.decodeFromString<PromoEvent>(encoded)
        assertEquals("promo_click", decoded.event)
        assertEquals("com.charleshartman.porchlightpress", decoded.sourcePackage)
        assertEquals("com.auracast.weather", decoded.targetPackage)
        assertEquals("settings", decoded.placement)
    }
}
