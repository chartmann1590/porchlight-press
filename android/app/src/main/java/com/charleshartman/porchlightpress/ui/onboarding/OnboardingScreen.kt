package com.charleshartman.porchlightpress.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.domain.Place
import com.charleshartman.porchlightpress.ui.components.PorchlightMark
import com.charleshartman.porchlightpress.ui.motion.PorchlightMotion
import com.charleshartman.porchlightpress.ui.motion.rememberReduceMotion
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.classicPaperBrush
import com.charleshartman.porchlightpress.ui.theme.modernSurfaceBrush

private val ALL_STEPS = OnboardingStep.entries

@Composable
fun OnboardingRoute(
    vm: OnboardingViewModel,
    activity: Activity,
    gpsClickHandler: (() -> Unit)? = null,
    notificationHandler: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { vm.onBack() }
    val classic = LocalIsClassic.current
    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())
    val reduce = rememberReduceMotion()
    Column(
        Modifier.fillMaxSize().then(bg).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        ProgressDots(current = state.step.ordinal, total = ALL_STEPS.size)
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (reduce) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (slideInHorizontally(tween(PorchlightMotion.slideMs(reduce))) { it / 4 } + fadeIn(tween(PorchlightMotion.fadeMs(reduce)))) togetherWith
                        (slideOutHorizontally(tween(PorchlightMotion.slideMs(reduce))) { -it / 4 } + fadeOut(tween(PorchlightMotion.fadeMs(reduce))))
                }
            },
            label = "onboarding-step",
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onContinue = vm::onContinue)
                OnboardingStep.LANGUAGE -> LanguageStep(vm)
                OnboardingStep.LOCATION -> LocationStep(vm, gpsClickHandler)
                OnboardingStep.CONFIRM -> ConfirmStep(vm)
                OnboardingStep.INTERESTS -> InterestsStep(vm)
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(vm, notificationHandler)
                OnboardingStep.PRIVACY -> PrivacyStep(vm, activity)
                OnboardingStep.DONE -> DoneStep(vm)
            }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().testTag("ob-progress"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val active = i == current
            val done = i < current
            val w by animateFloatAsState(if (active) 22f else 8f, label = "dot-w")
            Box(
                Modifier
                    .height(8.dp)
                    .width(w.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            active || done -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepNav(
    onBack: () -> Unit,
    onContinue: (() -> Unit)?,
    onSkip: (() -> Unit)? = null,
    continueLabel: String = "Continue",
    continueTag: String = "ob-continue",
) {
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("ob-back")) { Text("Back") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSkip != null) {
                TextButton(onClick = onSkip, modifier = Modifier.testTag("ob-skip")) { Text("Skip") }
            }
            if (onContinue != null) {
                Button(onClick = onContinue, modifier = Modifier.testTag(continueTag)) { Text(continueLabel) }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("step-welcome"), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PorchlightMark(size = 56.dp)
        Text("Welcome to Porchlight Press", style = MaterialTheme.typography.headlineSmall)
        Text("Your personal newspaper: local news first, free, with ads.", style = MaterialTheme.typography.bodyLarge)
        Text("Stories are AI-written briefs that always link the real reporting — and say so on every story.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("ob-continue"), shape = RoundedCornerShape(50)) { Text("Continue") }
    }
}

@Composable
private fun LanguageStep(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("step-language"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose your language", style = MaterialTheme.typography.headlineSmall)
        Text("The whole app — news, weather, even the PDF — is translated on your phone.")
        Column(Modifier.fillMaxWidth()) {
            state.supportedLanguages.forEach { tag ->
                val display = try {
                    java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.ENGLISH)
                } catch (e: Exception) {
                    tag
                }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectLanguage(tag) }
                        .testTag("ob-language-$tag").padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (state.language == tag) "◉ " else "○ ")
                    Text(if (display.isBlank()) tag else "$display ($tag)")
                }
            }
        }
        if (state.language != "en") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.wifiOnly,
                    onCheckedChange = vm::setWifiOnly,
                    modifier = Modifier.testTag("ob-wifi-only"),
                )
                Text("Download over Wi-Fi only (~30 MB)")
            }
            Button(
                onClick = vm::downloadModel,
                enabled = !state.modelDownloading,
                modifier = Modifier.testTag("ob-download-model"),
            ) {
                Text(if (state.modelDownloading) "Downloading…" else "Download language model")
            }
            if (state.modelDownloading) CircularProgressIndicator()
            state.modelNote?.let { Text(it, modifier = Modifier.testTag("ob-model-note")) }
        }
        StepNav(onBack = vm::onBack, onContinue = vm::onContinue)
    }
}

@Composable
private fun LocationStep(vm: OnboardingViewModel, gpsClickHandler: (() -> Unit)?) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) readLastKnown(context, vm) else vm.onGpsResult(false, null, null)
    }

    Column(Modifier.fillMaxWidth().testTag("step-location"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Where's your paper from?", style = MaterialTheme.typography.headlineSmall)
        Text("Your local news and weather depend on where you are. Your location stays on this phone.")
        OutlinedButton(
            onClick = {
                vm.chooseOption(LocationOption.GPS)
                if (gpsClickHandler != null) {
                    gpsClickHandler()
                } else if (hasCoarse(context)) {
                    readLastKnown(context, vm)
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("loc-gps"),
        ) { Text("Use my location") }
        if (state.gpsResolving) CircularProgressIndicator(Modifier.testTag("loc-resolving"))
        OutlinedButton(
            onClick = { vm.chooseOption(LocationOption.ZIP) },
            modifier = Modifier.fillMaxWidth().testTag("loc-zip"),
        ) { Text("Enter ZIP / postal code") }
        OutlinedButton(
            onClick = { vm.chooseOption(LocationOption.MANUAL) },
            modifier = Modifier.fillMaxWidth().testTag("loc-manual"),
        ) { Text("Pick manually") }

        when {
            state.locationOption == LocationOption.ZIP || state.gpsFallback -> ZipEntry(vm)
            state.locationOption == LocationOption.MANUAL -> ManualPickers(vm)
        }
        StepNav(onBack = vm::onBack, onContinue = vm::onContinue)
    }
}

private fun hasCoarse(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun readLastKnown(context: Context, vm: OnboardingViewModel): Boolean {
    if (!hasCoarse(context)) {
        vm.onGpsResult(false, null, null)
        return false
    }
    return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fix = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (fix != null) vm.onGpsResult(true, fix.latitude, fix.longitude) else vm.onGpsResult(true, null, null)
        true
    } catch (e: SecurityException) {
        vm.onGpsResult(false, null, null)
        false
    }
}

@Composable
private fun ZipEntry(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("zip-entry"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.zipCountry,
            onValueChange = vm::onZipCountry,
            label = { Text("Country (e.g. US)") },
            singleLine = true,
            modifier = Modifier.testTag("zip-country"),
        )
        OutlinedTextField(
            value = state.zipCode,
            onValueChange = vm::onZipCode,
            label = { Text("ZIP / postal code") },
            singleLine = true,
            modifier = Modifier.testTag("zip-code"),
        )
        Button(onClick = vm::submitZip, modifier = Modifier.testTag("zip-submit")) { Text("Look up") }
        state.zipError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("zip-error")) }
        state.zipOptions.forEach { option ->
            OutlinedButton(
                onClick = { vm.chooseZipOption(option) },
                modifier = Modifier.fillMaxWidth().testTag("zip-option-${option.id}"),
            ) { Text(option.label) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualPickers(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("manual-pickers"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(
            label = "Country",
            tag = "manual-country",
            options = state.countries,
            selected = state.countries.find { it.country == state.manualCountry }?.name ?: state.manualCountry,
            itemTag = { "pick-country-${it.country}" },
            itemLabel = { it.name },
            onSelect = { vm.setManualCountry(it.country) },
        )
        Picker(
            label = "State / Province",
            tag = "manual-admin1",
            options = state.admin1s,
            selected = state.admin1s.find { it.admin1 == state.manualAdmin1 }?.name ?: "Choose…",
            itemTag = { "pick-admin1-${it.admin1}" },
            itemLabel = { it.name },
            onSelect = { vm.setManualAdmin1(it.admin1) },
        )
        Picker(
            label = "County / Region",
            tag = "manual-admin2",
            options = state.counties,
            selected = state.manualAdmin2 ?: "Choose…",
            itemTag = { "pick-county-${it.name}" },
            itemLabel = { it.name },
            onSelect = { vm.setManualAdmin2(it.name) },
        )
        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = vm::setManualQuery,
            label = { Text("Search city") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("manual-search"),
        )
        // Plain Column (bounded to 50): this screen already scrolls, and a
        // LazyColumn inside a scrolling Column is a runtime crash.
        Column(Modifier.fillMaxWidth().testTag("manual-cities")) {
            state.cities.take(50).forEach { city ->
                Text(
                    city.name,
                    modifier = Modifier.fillMaxWidth().clickable { vm.setManualCity(city) }
                        .testTag("pick-city-${city.name}").padding(vertical = 10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    tag: String,
    options: List<PlaceDto>,
    selected: String,
    itemTag: (PlaceDto) -> String,
    itemLabel: (PlaceDto) -> String,
    onSelect: (PlaceDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag(tag),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(itemLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.testTag(itemTag(option)),
                )
            }
        }
    }
}

@Composable
private fun ConfirmStep(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    val place = state.place
    Column(Modifier.fillMaxWidth().testTag("step-confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Confirm your paper", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your paper: ${place?.label ?: "—"}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("confirm-label"),
        )
        Text("You'll get these sections:")
        state.confirmSections.forEach { Text("• $it") }
        when (state.localFeedAvailable) {
            true -> Text("There's a local feed for your town. 🎉", modifier = Modifier.testTag("confirm-feed-yes"))
            false -> Text(
                "No local feed yet — showing your region + state + national news instead.",
                modifier = Modifier.testTag("confirm-feed-no"),
            )
            null -> Text("Checking for a local feed…")
        }
        StepNav(
            onBack = vm::onBack,
            onContinue = vm::onContinue,
            continueLabel = "Looks good",
            continueTag = "ob-confirm",
        )
    }
}

@Composable
private fun InterestsStep(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("step-interests"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What do you care about?", style = MaterialTheme.typography.headlineSmall)
        Text("Optional — we'll boost these sections for you, on this phone only.")
        state.taxonomy.forEach { (id, label) ->
            FilterChip(
                selected = id in state.interests,
                onClick = { vm.toggleInterest(id) },
                label = { Text(label) },
                modifier = Modifier.testTag("interest-$id"),
            )
        }
        StepNav(onBack = vm::onBack, onContinue = vm::onContinue, onSkip = vm::onSkip)
    }
}

@Composable
private fun NotificationsStep(vm: OnboardingViewModel, notificationHandler: (() -> Unit)?) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxWidth().testTag("step-notifications"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stay in the loop?", style = MaterialTheme.typography.headlineSmall)
        Text("All optional, all rate-limited. You can change these any time in Settings.")
        ToggleRow("Severe weather", state.notifySevere, vm::setNotifySevere, "notif-severe")
        ToggleRow("Breaking local news", state.notifyBreaking, vm::setNotifyBreaking, "notif-breaking")
        ToggleRow("Morning / evening edition", state.notifyEditions, vm::setNotifyEditions, "notif-editions")
        StepNav(
            onBack = vm::onBack,
            onContinue = {
                val anyOn = state.notifySevere || state.notifyBreaking || state.notifyEditions
                if (anyOn && Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
                    if (notificationHandler != null) notificationHandler() else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.onContinue()
            },
            onSkip = vm::onSkip,
        )
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun PrivacyStep(vm: OnboardingViewModel, activity: Activity) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("step-privacy"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your privacy", style = MaterialTheme.typography.headlineSmall)
        Text("What leaves this phone: nothing you read. Weather requests send only your rounded ~10 km area. Ads (in the app only) use Google's consent form below when your region requires it. Crash and usage reports are off unless you opt in.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.analyticsConsent,
                onCheckedChange = vm::setAnalyticsConsent,
                modifier = Modifier.testTag("consent-analytics"),
            )
            Text("Help improve the app (usage stats, off by default)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.crashConsent,
                onCheckedChange = vm::setCrashConsent,
                modifier = Modifier.testTag("consent-crash"),
            )
            Text("Send crash reports (off by default)")
        }
        StepNav(
            onBack = vm::onBack,
            onContinue = { vm.acceptPrivacyAndContinue(activity) },
            onSkip = { vm.acceptPrivacyAndSkip(activity) },
            continueLabel = "Agree & continue",
            continueTag = "ob-privacy-continue",
        )
    }
}

@Composable
private fun DoneStep(vm: OnboardingViewModel) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().testTag("step-done"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PorchlightMark(size = 48.dp)
        Text("PORCHLIGHT PRESS", style = MaterialTheme.typography.headlineSmall)
        when (val sync = state.sync) {
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Loading ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.testTag("done-loading"))
                    Text("Fetching your first edition…")
                }
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Loaded -> {
                val n = sync.summary.storyCount
                Text(
                    "Your paper is ready: $n ${if (n == 1) "story" else "stories"} from ${sync.summary.locationLabel}.",
                    modifier = Modifier.testTag("done-loaded"),
                )
            }
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Offline ->
                Text("📵 ${sync.message}", modifier = Modifier.testTag("done-offline"))
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Error ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sync.message, modifier = Modifier.testTag("done-error"))
                    Button(onClick = vm::retrySync, modifier = Modifier.testTag("done-retry")) { Text("Retry") }
                }
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.UpdateRequired ->
                Text(
                    "This edition needs a newer app. Please update Porchlight Press.",
                    modifier = Modifier.testTag("done-update"),
                )
            is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Idle ->
                CircularProgressIndicator(Modifier.testTag("done-loading"))
        }
        if (state.sync !is com.charleshartman.porchlightpress.ui.onboarding.SyncUiState.Loading) {
            Button(onClick = vm::markReading, modifier = Modifier.testTag("done-finish")) {
                Text("Start reading")
            }
        }
    }
}
