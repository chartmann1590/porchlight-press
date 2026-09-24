package com.charleshartman.porchlightpress

import android.app.Activity
import com.charleshartman.porchlightpress.data.repo.ConsentRepository
import com.charleshartman.porchlightpress.data.repo.ConsentState
import com.charleshartman.porchlightpress.data.repo.FakeConsentGateway
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsentRepositoryTest {
    @Test
    fun startsUnknownAndResolvesThroughGateway() = runTest {
        val gateway = FakeConsentGateway(ConsentState.Obtained)
        val repo = ConsentRepository(gateway)
        assertEquals(ConsentState.Unknown, repo.state.value)
        assertEquals(0, gateway.requests)
        // No ad request may happen before this resolves; Phase 5 has no ads at all.
        assertEquals(ConsentState.Obtained, repo.requestConsent(mockk<Activity>(relaxed = true)))
        assertEquals(1, gateway.requests)
        assertEquals(ConsentState.Obtained, repo.state.value)
    }

    @Test
    fun propagatesNotRequired() = runTest {
        val repo = ConsentRepository(FakeConsentGateway(ConsentState.NotRequired))
        assertEquals(ConsentState.NotRequired, repo.requestConsent(mockk(relaxed = true)))
    }
}
