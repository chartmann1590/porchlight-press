package com.charleshartman.porchlightpress

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.data.ads.InterstitialController
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.PreferencesStore
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.repo.AndroidGeoLookup
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.GeoLookup
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import com.charleshartman.porchlightpress.data.repo.MlKitTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import com.charleshartman.porchlightpress.data.repo.UmpConsentGateway

/**
 * Manual DI container (single :app module; split only if it hurts).
 * UI tests swap repositories via [overrideForTests].
 */
class AppContainer(val context: Context) {
    val db: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    val prefs: PreferencesStore by lazy { PreferencesStore(context) }
    val feedApi: FeedApi by lazy { NetworkModule.feedApi(context) }

    var editionOverride: EditionRepository? = null
    var locationOverride: LocationRepository? = null
    var translationOverride: TranslationRepository? = null
    var consentOverride: ConsentRepository? = null
    var geoOverride: GeoLookup? = null

    private val realEdition: EditionRepository by lazy { EditionRepository(db) }
    private val realLocation: LocationRepository by lazy { LocationRepository(context, feedApi) }
    private val realTranslation: TranslationRepository by lazy {
        TranslationRepository(db, MlKitTranslatorEngine())
    }
    private val realConsent: ConsentRepository by lazy { ConsentRepository(UmpConsentGateway()) }
    private val realGeo: GeoLookup by lazy { AndroidGeoLookup(context) }

    val editionRepository: EditionRepository get() = editionOverride ?: realEdition
    val locationRepository: LocationRepository get() = locationOverride ?: realLocation
    val translationRepository: TranslationRepository get() = translationOverride ?: realTranslation
    val consentRepository: ConsentRepository get() = consentOverride ?: realConsent
    val geoLookup: GeoLookup get() = geoOverride ?: realGeo

    val adGate: AdMobGate by lazy { AdMobGate(context) }
    val interstitialController: InterstitialController by lazy { InterstitialController() }

    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    /** Test-only seam: replace repositories with fakes. */
    fun overrideForTests(
        edition: EditionRepository? = null,
        location: LocationRepository? = null,
        translation: TranslationRepository? = null,
        consent: ConsentRepository? = null,
        geo: GeoLookup? = null,
    ) {
        editionOverride = edition
        locationOverride = location
        translationOverride = translation
        consentOverride = consent
        geoOverride = geo
    }
}
