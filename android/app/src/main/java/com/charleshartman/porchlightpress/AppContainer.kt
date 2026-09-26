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
import com.charleshartman.porchlightpress.data.weather.AndroidCoordsResolver
import com.charleshartman.porchlightpress.data.weather.CoordsResolver
import com.charleshartman.porchlightpress.data.weather.MetNoProvider
import com.charleshartman.porchlightpress.data.weather.NwsProvider
import com.charleshartman.porchlightpress.data.weather.OkHttpWeatherFetcher
import com.charleshartman.porchlightpress.data.weather.WeatherFetcher
import com.charleshartman.porchlightpress.data.weather.WeatherRepository

/**
 * Manual DI container (single :app module; split only if it hurts).
 * UI tests swap repositories via [overrideForTests].
 */
class AppContainer(val context: Context) {
    val optionalServices by lazy { com.charleshartman.porchlightpress.data.OptionalServices(context) }
    val db: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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
    var weatherOverride: WeatherRepository? = null
    var coordsOverride: CoordsResolver? = null

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

    // -- Weather (Phase 7): NWS primary for US, MET Norway fallback/worldwide.
    // Providers fetch live; the repository implements the Room-backed
    // conditional cache (WeatherCacheStore) with per-bucket TTLs.
    val weatherFetcher: WeatherFetcher by lazy {
        OkHttpWeatherFetcher(NetworkModule.okHttp(context))
    }
    private val realCoords: CoordsResolver by lazy { AndroidCoordsResolver(context) }
    val coordsResolver: CoordsResolver get() = coordsOverride ?: realCoords
    private val weatherStore: com.charleshartman.porchlightpress.data.weather.WeatherCacheStore by lazy {
        com.charleshartman.porchlightpress.data.weather.RoomWeatherCacheStore(db)
    }
    private val realWeather: WeatherRepository by lazy {
        WeatherRepository(
            weatherStore,
            NwsProvider(weatherFetcher, weatherStore, null),
            MetNoProvider(weatherFetcher, weatherStore, null),
            coordsResolver,
        )
    }
    val weatherRepository: WeatherRepository
        get() = weatherOverride ?: realWeather

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
        weather: WeatherRepository? = null,
        coords: CoordsResolver? = null,
    ) {
        editionOverride = edition
        locationOverride = location
        translationOverride = translation
        consentOverride = consent
        geoOverride = geo
        weatherOverride = weather
        coordsOverride = coords
    }
}
