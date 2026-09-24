package com.charleshartman.porchlightpress.ui.util

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Time display via java.time in device zone; 12/24 h follows
 * DateFormat.is24HourFormat unless overridden in settings.
 */
object TimeFormat {

    fun formatEditionLabel(
        generatedAtIso: String?,
        kind: String?,
        context: android.content.Context,
        clockOverride: String = "system",
    ): String {
        val kindLabel = when (kind) {
            "morning" -> "Morning Edition"
            "afternoon" -> "Afternoon Edition"
            "evening" -> "Evening Edition"
            else -> "Latest Edition"
        }
        val time = formatTimeShort(generatedAtIso, context, clockOverride)
        return if (time.isBlank()) kindLabel else "$kindLabel \u00b7 Updated $time"
    }

    fun formatTimeShort(
        iso: String?,
        context: android.content.Context,
        clockOverride: String = "system",
    ): String {
        if (iso.isNullOrBlank()) return ""
        val zdt = parseIso(iso) ?: return ""
        val is24 = when (clockOverride) {
            "24h" -> true
            "12h" -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val pattern = if (is24) "H:mm" else "h:mm a"
        return try {
            val fmt = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            zdt.withZoneSameInstant(ZoneId.systemDefault()).format(fmt)
        } catch (e: Exception) {
            ""
        }
    }

    fun formatDateline(
        context: android.content.Context,
        placeLabel: String?,
    ): String {
        val date = try {
            ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
        } catch (e: Exception) {
            ""
        }
        return if (placeLabel.isNullOrBlank()) date else "$date \u00b7 $placeLabel"
    }

    fun formatSourceTime(iso: String?, context: android.content.Context, clockOverride: String = "system"): String {
        if (iso.isNullOrBlank()) return ""
        val zdt = parseIso(iso) ?: return ""
        return try {
            val pat = if (when (clockOverride) {
                    "24h" -> true
                    "12h" -> false
                    else -> DateFormat.is24HourFormat(context)
                }) "MMM d, H:mm" else "MMM d, h:mm a"
            val fmt = DateTimeFormatter.ofPattern(pat, Locale.getDefault())
            zdt.withZoneSameInstant(ZoneId.systemDefault()).format(fmt)
        } catch (e: Exception) {
            iso
        }
    }

    fun formatUpdatedLabel(iso: String?, context: android.content.Context, clockOverride: String = "system"): String {
        val t = formatTimeShort(iso, context, clockOverride)
        return if (t.isBlank()) "" else "Updated $t"
    }

    fun parseIso(iso: String): ZonedDateTime? {
        return try {
            // Handle trailing Z and offset forms.
            val normalized = iso.trim().replace("Z", "+00:00")
            // Try Instant parse first for strict ISO.
            try {
                Instant.parse(iso).atZone(ZoneId.systemDefault())
            } catch (e: Exception) {
                ZonedDateTime.parse(normalized)
            }
        } catch (e: Exception) {
            null
        }
    }
}
