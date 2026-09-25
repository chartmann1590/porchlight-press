package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.ads.NativeSlotPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slot-placement rules: cadence, density, no adjacency, never first/last. */
class NativeSlotPlannerTest {

    @Test
    fun cadenceEveryThirdSection() {
        assertFalse(NativeSlotPlanner.showAfterSection(0, 10, 100, false))
        assertFalse(NativeSlotPlanner.showAfterSection(1, 10, 100, false))
        assertTrue(NativeSlotPlanner.showAfterSection(2, 10, 100, false))
        assertFalse(NativeSlotPlanner.showAfterSection(3, 20, 100, false))
        assertFalse(NativeSlotPlanner.showAfterSection(4, 20, 100, false))
        assertTrue(NativeSlotPlanner.showAfterSection(5, 20, 100, false))
    }

    @Test
    fun neverDirectlyUnderWeatherCard() {
        // First slot position with no stories before it: never, even on cadence.
        assertFalse(NativeSlotPlanner.showAfterSection(2, 0, 100, false))
        assertTrue(NativeSlotPlanner.showAfterSection(2, 1, 100, false))
    }

    @Test
    fun atMostOneInFeedAdPerFiveStories() {
        // Only 4 stories since the previous ad: too soon.
        assertFalse(NativeSlotPlanner.showAfterSection(5, 10, 4, false))
        // Exactly 5: allowed.
        assertTrue(NativeSlotPlanner.showAfterSection(5, 10, 5, false))
    }

    @Test
    fun neverLastAboveAnchoredBanner() {
        // Final section: the anchored banner follows, so no native slot even
        // when cadence and density would allow it.
        assertFalse(NativeSlotPlanner.showAfterSection(2, 10, 100, true))
        assertFalse(NativeSlotPlanner.showAfterSection(5, 20, 100, true))
    }

    @Test
    fun customCadence() {
        assertTrue(NativeSlotPlanner.showAfterSection(1, 10, 100, false, everyNSections = 2))
        assertFalse(NativeSlotPlanner.showAfterSection(2, 10, 100, false, everyNSections = 2))
    }
}
