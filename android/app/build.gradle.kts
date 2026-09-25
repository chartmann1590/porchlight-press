import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Phase 9: the google-services plugin is applied only when the (gitignored)
// app/google-services.json exists, so a clean checkout builds without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Release AdMob IDs come ONLY from Gradle properties (-PadmobAppId=... etc.)
// supplied by the release workflow from GitHub secrets. Every other build
// uses Google's published TEST IDs. Never commit a real ca-app-pub ID.
fun prop(name: String, fallback: String): String =
    (findProperty(name) as String?)?.ifBlank { null } ?: fallback

val admobAppId = prop("admobAppId", "ca-app-pub-3940256099942544~3347511713")
val admobBannerId = prop("admobBannerId", "ca-app-pub-3940256099942544/9214589741")
val admobInterstitialId = prop("admobInterstitialId", "ca-app-pub-3940256099942544/1033173712")
val admobNativeId = prop("admobNativeId", "ca-app-pub-3940256099942544/2247696110")
val feedBaseUrl = prop("feedBaseUrl", "https://chartmann1590.github.io/porchlight-press/")

// Phase 9A release versioning: the release workflow passes
// -PversionCode=<github.run_number + VERSION_CODE_OFFSET> and
// -PversionName=<tag, e.g. v1.2.3>. Local builds fall back to 1 / 0.7.0.
// (Script top level: plain `val`, never `const val` — const is not allowed
// in Gradle Kotlin DSL scripts.)
val VERSION_CODE_OFFSET = 1000
val releaseVersionCode =
    prop("versionCode", System.getenv("VERSION_CODE") ?: "1").toIntOrNull() ?: 1
val releaseVersionName = prop("versionName", System.getenv("VERSION_NAME") ?: "0.7.0")

// Phase 9A release signing: keystore path + credentials come ONLY from
// environment (CI) or Gradle properties (-PreleaseKeystorePath=... etc.)
// supplied by the release workflow from GitHub secrets. Never commit them.
// When no keystore is present (local dev, CI debug builds) the release
// build type stays unsigned and still compiles (R8 + shrinking still run).
fun releaseKeystorePath(): String? =
    System.getenv("RELEASE_KEYSTORE_PATH")
        ?: (findProperty("releaseKeystorePath") as String?)?.ifBlank { null }
fun releaseKeystorePassword(): String? =
    System.getenv("RELEASE_KEYSTORE_PASSWORD")
        ?: (findProperty("releaseKeystorePassword") as String?)?.ifBlank { null }
fun releaseKeyAlias(): String? =
    System.getenv("RELEASE_KEY_ALIAS")
        ?: (findProperty("releaseKeyAlias") as String?)?.ifBlank { null }
fun releaseKeyPassword(): String? =
    System.getenv("RELEASE_KEY_PASSWORD")
        ?: (findProperty("releaseKeyPassword") as String?)?.ifBlank { null }

android {
    namespace = "com.charleshartman.porchlightpress"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.charleshartman.porchlightpress"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "FEED_BASE_URL", "\"$feedBaseUrl\"")
        buildConfigField("int", "SUPPORTED_FEED_API_MAJOR", "1")
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "ADMOB_NATIVE_ID", "\"$admobNativeId\"")
    }

    signingConfigs {
        create("release") {
            val ksPath = releaseKeystorePath()
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = releaseKeystorePassword()
                keyAlias = releaseKeyAlias()
                keyPassword = releaseKeyPassword()
            }
        }
    }

    buildTypes {
        debug {
            // Debug always uses test ad IDs even if properties are supplied.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
            buildConfigField(
                "String",
                "ADMOB_BANNER_ID",
                "\"ca-app-pub-3940256099942544/9214589741\"",
            )
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_ID",
                "\"ca-app-pub-3940256099942544/1033173712\"",
            )
            buildConfigField(
                "String",
                "ADMOB_NATIVE_ID",
                "\"ca-app-pub-3940256099942544/2247696110\"",
            )
            buildConfigField(
                "String",
                "FEED_BASE_URL",
                "\"${prop("feedBaseUrl", "https://chartmann1590.github.io/porchlight-press/")}\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign only when the CI keystore is present; otherwise leave the
            // release build unsigned so local/CI debug-path builds keep working.
            val ksPath = releaseKeystorePath()
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room (single source of truth) + DataStore (preferences).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // Network: Retrofit + OkHttp + kotlinx.serialization.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.splashscreen)

    // Ads + UMP (Phase 6: banner/native/interstitial with test IDs, consent-gated).
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.androidx.browser)

    // Image loading: Coil 2 with crossfade.
    implementation(libs.coil.compose)

    // Paging 3 for section lists (Room PagingSource).
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // On-device translation (ML Kit, free, no key).
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
