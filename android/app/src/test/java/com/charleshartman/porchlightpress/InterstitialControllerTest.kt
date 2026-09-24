package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.ads.AdConfig
import com.charleshartman.porchlightpress.data.ads.InterstitialController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frequency-cap unit tests (Phase 6/9 spec): every N articles + min interval. */
class InterstitialControllerTest {

    @Test
    fun defaultsMatchRemoteConfigSpec() {
        val c = AdConfig()
        assertTrue(c.enabled)
        assertEquals(3, c.adsEveryNSections)
        assertEquals(3, c.interstitialEveryN)
        assertEquals(180, c.interstitialMinIntervalSec)
    }

    @Test
    fun showsOnlyEveryNthReturn() {
        val ctl = InterstitialController(AdConfig(interstitialEveryN = 3, interstitialMinIntervalSec = 0))
        assertFalse(ctl.onArticleReturn(1_000))
        assertFalse(ctl.onArticleReturn(2_000))
        assertTrue(ctl.onArticleReturn(3_000))
        assertFalse(ctl.onArticleReturn(4_000))
        assertFalse(ctl.onArticleReturn(5_000))
        assertTrue(ctl.onArticleReturn(6_000))
    }

    @Test
    fun respectsMinInterval() {
        val ctl = InterstitialController(AdConfig(interstitialEveryN = 1, interstitialMinIntervalSec = 180))
        assertTrue(ctl.onArticleReturn(0))
        // 60s later: count hits but interval blocks.
        assertFalse(ctl.onArticleReturn(60_000))
        // 181s after first show: allowed.
        assertTrue(ctl.onArticleReturn(181_000))
    }

    @Test
    fun neverShowsBeforeNthReturn() {
        val ctl = InterstitialController(AdConfig(interstitialEveryN = 3, interstitialMinIntervalSec = 0))
        // First two returns (e.g. launch/onboarding period) never show.
        assertFalse(ctl.onArticleReturn(1_000))
        assertFalse(ctl.onArticleReturn(2_000))
    }
}
