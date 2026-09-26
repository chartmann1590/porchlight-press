package com.charleshartman.porchlightpress.data.repo

import android.app.Activity
import com.charleshartman.porchlightpress.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * UMP consent for the onboarding privacy step (MASTER_PLAN §13).
 * Phase 5 only wires the consent flow in test mode; real ad IDs arrive via
 * Gradle properties in the release workflow (Phase 9) and are never in the repo.
 */
sealed interface ConsentState {
    data object Unknown : ConsentState
    data object NotRequired : ConsentState
    data object Obtained : ConsentState
    data class FormError(val message: String) : ConsentState
}

interface ConsentGateway {
    suspend fun requestUpdate(activity: Activity): ConsentState
    fun privacyOptionsRequired(): Boolean
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit)
}

class UmpConsentGateway : ConsentGateway {
    private var optionsRequired = false
    override suspend fun requestUpdate(activity: Activity): ConsentState =
        suspendCancellableCoroutine { cont ->
            val paramsBuilder = ConsentRequestParameters.Builder()
            if (BuildConfig.DEBUG) {
                // Test mode: force EEA geography so the form is exercisable on
                // the US test device with Google's test IDs.
                paramsBuilder.setConsentDebugSettings(
                    ConsentDebugSettings.Builder(activity)
                        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                        .build(),
                )
            }
            val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
            consentInfo.requestConsentInfoUpdate(
                activity,
                paramsBuilder.build(),
                {
                    optionsRequired = consentInfo.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                    if (!consentInfo.isConsentFormAvailable) {
                        if (cont.isActive) cont.resume(if (consentInfo.canRequestAds()) ConsentState.NotRequired
                            else ConsentState.FormError("Consent is not available yet. Please try again."))
                        return@requestConsentInfoUpdate
                    }
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (!cont.isActive) return@loadAndShowConsentFormIfRequired
                        if (formError != null) {
                            cont.resume(ConsentState.FormError(formError.message))
                        } else if (consentInfo.privacyOptionsRequirementStatus ==
                            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                        ) {
                            // Form was shown and dismissed; consent recorded.
                            cont.resume(ConsentState.Obtained)
                        } else {
                            cont.resume(ConsentState.NotRequired)
                        }
                    }
                },
                { if (cont.isActive) cont.resume(ConsentState.FormError(it.message)) },
            )
        }

    override fun privacyOptionsRequired(): Boolean = optionsRequired

    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDone() }
    }
}

class FakeConsentGateway(var state: ConsentState = ConsentState.Obtained) : ConsentGateway {
    var requests = 0
    override suspend fun requestUpdate(activity: Activity): ConsentState {
        requests++
        return state
    }

    override fun privacyOptionsRequired(): Boolean = false
    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) = onDone()
}

class ConsentRepository(private val gateway: ConsentGateway) {
    fun privacyOptionsRequired(): Boolean = gateway.privacyOptionsRequired()

    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        gateway.showPrivacyOptions(activity, onDone)
    }
    private val _state = MutableStateFlow<ConsentState>(ConsentState.Unknown)
    val state: StateFlow<ConsentState> = _state

    /** Must resolve before any ad request (no ads in Phase 5; enforced for Phase 9). */
    suspend fun requestConsent(activity: Activity): ConsentState {
        val next = gateway.requestUpdate(activity)
        _state.value = next
        return next
    }
}
