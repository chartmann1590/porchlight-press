package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.work.AlertCheckLogic
import org.junit.Assert.assertEquals
import org.junit.Test

/** Alert dedupe: Warning-level, not-yet-notified IDs only. */
class AlertDedupeTest {

    private fun alert(id: String, event: String, severity: String) = WeatherAlert(
        id = id, event = event, headline = "$event headline",
        description = "desc", severity = severity,
    )

    private val three = listOf(
        alert("urn:tornado", "Tornado Warning", "Extreme"),
        alert("urn:flood", "Flood Warning", "Severe"),
        alert("urn:heat", "Heat Advisory", "Minor"),
    )

    @Test
    fun warningsOnlyFirstRun() {
        val fresh = AlertCheckLogic.newWarnings(three, emptySet())
        assertEquals(listOf("urn:tornado", "urn:flood"), fresh.map { it.id })
    }

    @Test
    fun alreadyNotifiedAreSkipped() {
        val fresh = AlertCheckLogic.newWarnings(three, setOf("urn:tornado", "urn:flood"))
        assertEquals(emptyList<WeatherAlert>(), fresh)
    }

    @Test
    fun fixtureTriggersExactlyOneNotification() {
        // Tornado already notified last run; flood is new; heat never notifies.
        val fresh = AlertCheckLogic.newWarnings(three, setOf("urn:tornado", "urn:heat"))
        assertEquals(listOf("urn:flood"), fresh.map { it.id })
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(emptyList<WeatherAlert>(), AlertCheckLogic.newWarnings(emptyList(), emptySet()))
    }

    @Test
    fun watchWithSevereSeverityNotifies() {
        val watch = alert("urn:watch", "Flood Watch", "Severe")
        assertEquals(listOf("urn:watch"), AlertCheckLogic.newWarnings(listOf(watch), emptySet()).map { it.id })
    }
}
