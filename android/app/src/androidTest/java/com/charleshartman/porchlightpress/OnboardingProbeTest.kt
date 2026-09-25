package com.charleshartman.porchlightpress

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.PreferencesStore
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.FakeConsentGateway
import com.charleshartman.porchlightpress.data.repo.ConsentState
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.data.repo.AndroidGeoLookup
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingStep
import com.charleshartman.porchlightpress.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device onboarding probe: drives the real [OnboardingViewModel] with the
 * LIVE feed API (real us-postal.json) through ZIP → confirm → interests →
 * notifications, logging the place country and the Severe default at each
 * step. Isolates whether the US auto-default misfires with live data.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingProbeTest {
    @Test
    fun probeSevereDefaultWithLivePostal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = PreferencesStore(context)
        val api = NetworkModule.feedApi(context)
        val vm = OnboardingViewModel(
            SavedStateHandle(), prefs,
            EditionRepository(db),
            LocationRepository(context, api),
            TranslationRepository(db, FakeTranslatorEngine()),
            ConsentRepository(FakeConsentGateway(ConsentState.Obtained)),
            AndroidGeoLookup(context), api,
            listOf("local" to "Local"),
            db.savedLocationDao(),
        )
        try {
            vm.onContinue() // WELCOME -> LANGUAGE
            vm.onContinue() // LANGUAGE -> LOCATION
            assertEquals(OnboardingStep.LOCATION, vm.state.value.step)
            vm.onZipCode("12308")
            vm.submitZip()
            val deadline = System.currentTimeMillis() + 30_000
            while (vm.state.value.step != OnboardingStep.CONFIRM && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
            val place = vm.state.value.place
            Log.i(
                "PorchlightProbe",
                "confirm step=${vm.state.value.step} place=${place?.label} " +
                    "country=${place?.country} admin1=${place?.admin1}",
            )
            assertEquals(OnboardingStep.CONFIRM, vm.state.value.step)
            vm.onContinue() // CONFIRM -> INTERESTS
            assertEquals(OnboardingStep.INTERESTS, vm.state.value.step)
            Log.i(
                "PorchlightProbe",
                "interests placeCountry=${vm.state.value.place?.country}",
            )
            vm.onContinue() // INTERESTS -> NOTIFICATIONS
            assertEquals(OnboardingStep.NOTIFICATIONS, vm.state.value.step)
            Log.i(
                "PorchlightProbe",
                "notifications notifySevere=${vm.state.value.notifySevere}",
            )
            assertTrue(
                "Severe default should be ON for US place (country=${vm.state.value.place?.country})",
                vm.state.value.notifySevere,
            )
        } finally {
            db.close()
        }
    }
}
