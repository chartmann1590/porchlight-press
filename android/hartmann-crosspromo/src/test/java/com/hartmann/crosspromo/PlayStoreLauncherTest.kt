package com.hartmann.crosspromo

import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreLauncherTest {

    @Test
    fun testBuildReferrerString() {
        val referrer = PlayStoreLauncher.buildReferrerString(
            sourcePackage = "com.charleshartman.porchlightpress",
            targetPackage = "com.auracast.weather"
        )

        assertTrue(referrer.contains("utm_source=com.charleshartman.porchlightpress"))
        assertTrue(referrer.contains("utm_medium=crosspromo"))
        assertTrue(referrer.contains("utm_campaign=hartmann_crosspromo"))
        assertTrue(referrer.contains("utm_content=com.auracast.weather"))
    }
}
