# Hartmann Studios Cross-Promotion Android SDK

Lightweight, production-ready Android library module for cross-promoting Hartmann Studios apps across your Android applications.

## Integration

### 1. Include in `settings.gradle.kts`
```kotlin
include(":hartmann-crosspromo")
```

### 2. Add dependency in your app's `build.gradle.kts`
```kotlin
dependencies {
    implementation(project(":hartmann-crosspromo"))
}
```

### 3. Initialize in `Application` class or `MainActivity`
```kotlin
HartmannCrossPromo.initialize(
    context = applicationContext,
    apiBaseUrl = "https://hartmann-crosspromo.charles-h-hartmann1.workers.dev"
)
```
- Host package name is detected automatically via `context.packageName` (no manual configuration required).
- Current app is automatically excluded from recommendations.
- Sets up Backend and Firebase Analytics tracking (via safe reflection).

### User opt-out (recommended)

Wire a user setting to the SDK so users can hide suggestions entirely (components render nothing, zero network calls):

```kotlin
HartmannCrossPromo.setUserOptOut(true)   // hides all promos, persists across launches
HartmannCrossPromo.setUserOptOut(false)  // re-enable
HartmannCrossPromo.isUserOptedOut()      // restore toggle state in your settings UI
```

---

## Jetpack Compose Usage

### Horizontal Carousel (Recommended for Home or Bottom of Screens)
```kotlin
HartmannPromoCarousel(
    placement = "home",
    limit = 3,
    title = "More from Hartmann Studios",
    subtitle = "Explore more apps from the same creator"
)
```

### Single Card / Compact Row (Recommended for Settings or List Footers)
```kotlin
HartmannPromoRow(
    placement = "settings",
    title = "More from Hartmann Studios"
)
```

---

## Traditional Android View / XML Usage

Add directly into any XML layout:
```xml
<com.hartmann.crosspromo.ui.HartmannPromoView
    android:id="@+id/hartmannPromo"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:promoPlacement="settings"
    app:promoLimit="3"
    app:promoTitle="More from Hartmann Studios" />
```

---

## Key Design Principles

1. **Silent Fallback**: Cross-promotion is non-critical. If device is offline and no cached apps exist, the component renders nothing (`Box {}` / hidden). It NEVER displays error messages, blank boxes, or placeholder ads.
2. **Accurate Impression Tracking**: Impressions are recorded only while a card is actually visible on screen (LazyList visibility in the carousel, layout bounds in the row), deduplicated per session *per placement* so one surface cannot starve another.
3. **Safe Play Store Launching**: Tries `market://details?id=...` with Google Play UTM attribution, falls back safely to browser, and NEVER crashes if Play Store is missing. NEVER downloads APKs.
4. **Stale-While-Revalidate**: Cached recommendations load instantaneously without showing a loading spinner. Fresh data is fetched in the background, and cache staleness honors the server-provided `expiresAt`.
5. **Small-Catalog Safety**: Session exclusions are scoped per placement and capped at 2 packages, so the exclusion list can never exclude the whole catalog into silence.
