// AndroidOnly: WP-303 Actual onboarding host through the production pairing coordinator and Room-backed App graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.onboarding.AppStateOnboardingHost
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingConsentIntegrationTest : RoomProcessTest() {
    @Test fun successfulPairingReturnsConnectedOnlyAfterTheRealServiceGraphIsWired() = runTest {
        val h = ContainerHarness(this, store)
        val connectGate = CompletableDeferred<Unit>()
        try {
            val leftover = UUID.randomUUID()
            val selected = UUID.randomUUID()
            h.pairing.registered[leftover] = "Leftover radio"
            h.pairing.discovery = { selected }
            h.links.configureRadio = { it.connectGate = connectGate }
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            val confirmation = assertNotNull(h.appState.confirmSystemPairingSetup())
            h.eventually("new radio connect", virtualBudgetMillis = 10_000) { h.links.radios.isNotEmpty() }
            assertFalse(request.isCompleted)
            assertFalse(confirmation.isCompleted)
            assertNull(h.appState.services)
            assertEquals(0, h.appState.servicesVersion.value)
            connectGate.complete(Unit)
            h.eventually("completed fresh pairing", virtualBudgetMillis = 10_000) { request.isCompleted }
            assertEquals(OnboardingConnectOutcome.Connected, request.await())
            confirmation.join()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            assertEquals(graph, h.appState.services)
            assertEquals(graph.syncCoordinator, h.appState.syncCoordinator)
            assertTrue(h.appState.servicesVersion.value > 0)
            assertEquals(listOf(leftover), h.pairing.removed)
            assertFalse(h.appState.connectionUI.isBusy)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally {
            connectGate.complete(Unit)
            h.container.close()
        }
    }

    @Test fun actualOnboardingHostWaitsForConsentAndRejectingPreservesTheRegistry() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("mounted setup state") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            assertFalse(request.isCompleted)
            assertEquals(0, h.pairing.removeCalls)
            assertTrue(h.links.links.isEmpty())
            h.appState.cancelSystemPairingSetup()
            assertEquals(OnboardingConnectOutcome.Cancelled, request.await())
            assertEquals(mapOf(id to "Leftover radio"), h.pairing.registered)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun actualOnboardingHostOnlyRemovesConfirmedUnsavedAssociations() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            val sibling = UUID.randomUUID()
            h.pairing.registered[sibling] = "Unconfirmed sibling"
            assertNotNull(h.appState.confirmSystemPairingSetup()).join()
            request.await()
            assertEquals(listOf(id), h.pairing.removed)
            assertEquals(mapOf(sibling to "Unconfirmed sibling"), h.pairing.registered)
            assertFalse(h.appState.connectionUI.isBusy)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun actualOnboardingHostCancellationReleasesConsentWithoutTouchingTheRegistry() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
            assertEquals(mapOf(id to "Leftover radio"), h.pairing.registered)
            assertFalse(h.appState.connectionUI.isBusy)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun decliningWhileConnectedDoesNotReportTheOldRadioAsANewPairingSuccess() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val live = assertNotNull(h.manager.connectedDevice)
            val graph = assertNotNull(h.sessions.current)
            h.pairing.registered[UUID.randomUUID()] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            h.appState.cancelSystemPairingSetup()
            assertEquals(OnboardingConnectOutcome.Cancelled, request.await())
            assertEquals(live.id, h.manager.connectedDevice?.id)
            assertEquals(graph, h.sessions.current)
            assertFalse(graph.isTornDown)
            assertEquals(0, h.pairing.removeCalls)
        } finally { h.container.close() }
    }
}
