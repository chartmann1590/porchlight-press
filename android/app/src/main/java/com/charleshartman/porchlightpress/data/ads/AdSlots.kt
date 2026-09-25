package com.charleshartman.porchlightpress.data.ads

/**
 * Pure placement rules for the native in-feed slot (unit-tested):
 * - section cadence: after every [everyNSections] sections (Remote Config
 *   `pp_ads_every_n_sections`, default 3);
 * - density: at most one in-feed ad per 5 stories;
 * - never directly under the weather card (at least one story first);
 * - never adjacent to another ad, including the anchored banner that always
 *   follows the last section (so no slot after the final section).
 */
object NativeSlotPlanner {
    fun showAfterSection(
        sectionIndex: Int,
        storiesBeforeSlot: Int,
        storiesSinceLastAd: Int,
        isLastSection: Boolean,
        everyNSections: Int = 3,
    ): Boolean {
        if (isLastSection) return false
        if ((sectionIndex + 1) % everyNSections.coerceAtLeast(1) != 0) return false
        if (storiesBeforeSlot <= 0) return false
        if (storiesSinceLastAd < 5) return false
        return true
    }
}
