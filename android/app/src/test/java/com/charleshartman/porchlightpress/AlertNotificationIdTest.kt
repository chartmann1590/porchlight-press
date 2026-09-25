package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.work.AlertNotifications
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stable per-alert notification IDs: same ID → same code (replace, don't
 * stack); distinct IDs → distinct codes (no hashCode collisions). */
class AlertNotificationIdTest {

    @Test
    fun sameIdMapsToSameCode() {
        val a = AlertNotifications.notificationIdFor("urn:oid:test-1")
        val b = AlertNotifications.notificationIdFor("urn:oid:test-1")
        assertEquals(a, b)
    }

    @Test
    fun distinctIdsMapToDistinctCodes() {
        val ids = (1..200).map { "urn:oid:2.49.0.1.840.0.test-$it" }
        val codes = ids.map { AlertNotifications.notificationIdFor(it) }.toSet()
        assertEquals(ids.size, codes.size)
    }

    @Test
    fun codesArePositive() {
        // NotificationManager + PendingIntent codes behave best non-negative.
        repeat(50) { i ->
            val code = AlertNotifications.notificationIdFor("urn:probe:$i")
            assertEquals(true, code > 0)
        }
    }
}
