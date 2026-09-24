package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.remote.PostalEntryDto
import com.charleshartman.porchlightpress.data.repo.ZipResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipResolverTest {
    private val schenectady = PostalEntryDto(
        postal = "12308", country = "US", admin1 = "US-NY",
        admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region",
    )
    private val albany = PostalEntryDto(
        postal = "12207", country = "US", admin1 = "US-NY",
        admin2 = "Albany County", city = "Albany",
        metro = "us-ny-capital-region",
    )

    @Test
    fun normalizesPlainZip() {
        assertEquals("12308", ZipResolver.normalizeUsZip("12308"))
    }

    @Test
    fun trimsZipPlus4() {
        assertEquals("12308", ZipResolver.normalizeUsZip("12308-1234"))
        assertEquals("12308", ZipResolver.normalizeUsZip("12308 1234"))
        assertEquals("12308", ZipResolver.normalizeUsZip("  12308-0001  "))
    }

    @Test
    fun rejectsBadZip() {
        assertNull(ZipResolver.normalizeUsZip("1234"))
        assertNull(ZipResolver.normalizeUsZip("abcde"))
        assertNull(ZipResolver.normalizeUsZip(""))
        assertNull(ZipResolver.normalizeUsZip("123456"))
    }

    @Test
    fun resolvesValidZip() {
        val out = ZipResolver.resolveUs("12308", listOf(schenectady, albany))
        assertTrue(out is ZipResolver.Outcome.Resolved)
        val place = (out as ZipResolver.Outcome.Resolved).place
        assertEquals("Schenectady, NY", place.label)
        assertEquals("Schenectady", place.city)
        assertEquals("us-ny-capital-region", place.metro)
    }

    @Test
    fun unknownCodeIsInvalidWithManualHint() {
        val out = ZipResolver.resolveUs("99999", listOf(schenectady))
        assertTrue(out is ZipResolver.Outcome.Invalid)
        assertTrue((out as ZipResolver.Outcome.Invalid).reason.contains("manually"))
    }

    @Test
    fun multiTownCodeIsAmbiguous() {
        val other = schenectady.copy(city = "Rotterdam", admin2 = "Schenectady County")
        val out = ZipResolver.resolveUs("12308", listOf(schenectady, other))
        assertTrue(out is ZipResolver.Outcome.Ambiguous)
        assertEquals(2, (out as ZipResolver.Outcome.Ambiguous).options.size)
    }

    @Test
    fun genericPostalIsCaseInsensitive() {
        val entry = PostalEntryDto(postal = "SW1A 1AA", country = "GB", city = "London")
        val out = ZipResolver.resolveGeneric("sw1a 1aa", listOf(entry))
        assertTrue(out is ZipResolver.Outcome.Resolved)
    }

    @Test
    fun bareNineDigitsIsAcceptedOnlyWhenWholeInputIsExactlyNineDigits() {
        // ZIP+4 without dash – exactly 9 digits after trim should return first 5.
        assertEquals("12308", ZipResolver.normalizeUsZip("123081234"))
        assertEquals("12308", ZipResolver.normalizeUsZip("  123081234  "))
        // Not bare 9: 10 digits, 8 digits, or 9 digits embedded in longer text must be rejected.
        assertNull(ZipResolver.normalizeUsZip("1234567890"))
        assertNull(ZipResolver.normalizeUsZip("12345678"))
        assertNull(ZipResolver.normalizeUsZip("123456789 extra"))
        assertNull(ZipResolver.normalizeUsZip("prefix 123456789"))
        assertNull(ZipResolver.normalizeUsZip("12345-67890"))
        assertNull(ZipResolver.normalizeUsZip("a123456789"))
        assertNull(ZipResolver.normalizeUsZip("123456789b"))
    }

    @Test
    fun nineDigitSubstringInsideLongerTextIsNotMatched() {
        // Ensure the \d{9} does not match a 9-digit substring inside a phone-like string.
        assertNull(ZipResolver.normalizeUsZip("Call 123456789"))
        assertNull(ZipResolver.normalizeUsZip("SSN 123-45-6789"))
        assertNull(ZipResolver.normalizeUsZip("123456789-1234"))
    }

    @Test
    fun zipPlus4EdgeCases() {
        assertNull(ZipResolver.normalizeUsZip("12308-"))
        assertNull(ZipResolver.normalizeUsZip("12308-123"))
        assertNull(ZipResolver.normalizeUsZip("12308 123"))
        assertNull(ZipResolver.normalizeUsZip("1230-1234"))
        assertEquals("12308", ZipResolver.normalizeUsZip("12308-0000"))
    }
}
