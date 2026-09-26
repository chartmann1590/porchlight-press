// Top-level build: plugin versions only (applied in :app). The
// google-services plugin is applied conditionally in app/build.gradle.kts
// (only when app/google-services.json exists) so clean checkouts build.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
