package one.meshcore.shared.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.TAG
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.WINDOW
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDiscoveryLifetimeTest {
    @Test
    fun remountKeepsTheModelAndReadsCurrentLocalizedCopy() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        fixture.send = { throw MeshBridgeException(BridgeErrorCode.TIMEOUT, "timeout") }
        assertSame(fixture.model, fixture.workspace.bind(fixture.messages("New copy")))
        fixture.workspace.scan()
        runCurrent()
        assertEquals("New copy", fixture.model.state.value.errorMessage)
    }

    @Test
    fun completedOwnerWaitsThenRebindsWithoutResendingOrExtendingTheDeadline() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        var subscriptions = 0
        fixture.events = flow { subscriptions++ }
        fixture.workspace.scan()
        runCurrent()
        assertEquals(1, subscriptions)
        assertTrue(fixture.model.state.value.isScanning)
        advanceTimeBy(WINDOW / 2)
        val replacement = MutableSharedFlow<MeshEvent>()
        fixture.events = replacement
        fixture.workspace.reattach()
        runCurrent()
        backgroundScope.launch { replacement.emit(response()) }
        runCurrent()
        assertEquals(1, fixture.model.state.value.results.size)
        assertEquals(1, fixture.sendCalls)
        advanceTimeBy(WINDOW / 2)
        runCurrent()
        assertFalse(fixture.model.state.value.isScanning)
        assertNull(fixture.model.state.value.errorMessage)
    }

    @Test
    fun anObsoleteFlowCannotPublishWhileWaitingForReattachment() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val old = fixture.events as MutableSharedFlow<MeshEvent>
        fixture.workspace.scan()
        runCurrent()
        fixture.events = MutableSharedFlow<MeshEvent>()
        backgroundScope.launch { old.emit(response()) }
        runCurrent()
        assertTrue(fixture.model.state.value.results.isEmpty())
        fixture.workspace.reattach()
        runCurrent()
        assertEquals(0, old.subscriptionCount.value)
    }

    @Test
    fun aReconnectWithoutALivePartitionRetainsTheOriginalScan() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        fixture.workspace.scan()
        runCurrent()
        fixture.radio = null
        fixture.events = null
        advanceTimeBy(WINDOW / 2)
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        fixture.radio = DiscoveryLifetimeFixture.RADIO
        val replacement = MutableSharedFlow<MeshEvent>()
        fixture.events = replacement
        fixture.workspace.reattach()
        runCurrent()
        backgroundScope.launch { replacement.emit(response()) }
        runCurrent()
        assertEquals(1, fixture.model.state.value.results.size)
        assertEquals(1, fixture.sendCalls)
        advanceTimeBy(WINDOW / 2)
        runCurrent()
        assertFalse(fixture.model.state.value.isScanning)
    }

    @Test
    fun aForegroundClockJumpExpiresAHiddenScanWithoutAnError() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        fixture.events = emptyFlow()
        fixture.workspace.scan()
        runCurrent()
        fixture.clock = WINDOW
        fixture.workspace.expireDeadline()
        runCurrent()
        assertFalse(fixture.model.state.value.isScanning)
        assertTrue(fixture.model.state.value.hasScannedOnce)
        assertNull(fixture.model.state.value.errorMessage)
    }

    @Test
    fun deadlineStartsAfterThePendingSendAcknowledges() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Long>()
        fixture.send = { ack.await() }
        fixture.workspace.scan()
        runCurrent()
        fixture.clock = WINDOW * 2
        fixture.workspace.expireDeadline()
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        ack.complete(TAG)
        runCurrent()
        fixture.clock += WINDOW - 1
        fixture.workspace.expireDeadline()
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        fixture.clock++
        fixture.workspace.expireDeadline()
        runCurrent()
        assertFalse(fixture.model.state.value.isScanning)
    }

    @Test
    fun noncooperativeSendCannotPublishAfterResetOrCancelANewerScan() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Long>()
        fixture.send = { withContext(NonCancellable) { ack.await() } }
        fixture.workspace.scan()
        runCurrent()
        fixture.workspace.reset()
        runCurrent()
        fixture.send = { TAG }
        fixture.workspace.scan()
        runCurrent()
        ack.complete(TAG)
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        assertTrue(fixture.model.state.value.results.isEmpty())
        assertNull(fixture.model.state.value.errorMessage)
        assertEquals(2, fixture.sendCalls)
    }

    @Test
    fun aChangedRadioRejectsLateFailureAndResponses() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Long>()
        fixture.send = { ack.await() }
        fixture.workspace.scan()
        runCurrent()
        fixture.radio = DiscoveryLifetimeFixture.OTHER_RADIO
        ack.completeExceptionally(MeshBridgeException(BridgeErrorCode.TIMEOUT, "old"))
        runCurrent()
        assertNull(fixture.model.state.value.errorMessage)
        fixture.workspace.reset()
        runCurrent()
        assertFalse(fixture.model.state.value.isScanning)
        assertTrue(fixture.model.state.value.results.isEmpty())
    }

    @Test
    fun queuedLifecycleResetAndRebindRejectAReplacementOwner() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        var current = true
        fixture.workspace.reset { current }
        fixture.workspace.reattach { current }
        current = false
        fixture.model.scan(backgroundScope)
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        assertEquals(1, fixture.sendCalls)
    }

    @Test
    fun stoppedSessionCodesAreSilentButTimeoutAndUnknownRemainFailures() = runTest {
        for (code in listOf(
            BridgeErrorCode.NOT_CONNECTED,
            BridgeErrorCode.CONNECTION_LOST,
            BridgeErrorCode.SESSION_NOT_STARTED,
            BridgeErrorCode.TIMEOUT,
            BridgeErrorCode.UNKNOWN,
        )) {
            val fixture = DiscoveryLifetimeFixture(backgroundScope)
            fixture.send = { throw MeshBridgeException(code, "native") }
            fixture.workspace.scan()
            runCurrent()
            val expected = if (code == BridgeErrorCode.TIMEOUT ||
                code == BridgeErrorCode.UNKNOWN
            ) {
                NODE_DISCOVERY_SCAN_FAILED
            } else {
                null
            }
            assertEquals(expected, fixture.model.state.value.errorMessage, code.name)
            assertFalse(fixture.model.state.value.isScanning)
        }
    }

    @Test
    fun stoppedEventOwnerWaitsForReadyInsteadOfReportingFailure() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        fixture.events = flow { throw MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "stopped") }
        fixture.workspace.scan()
        runCurrent()
        assertTrue(fixture.model.state.value.isScanning)
        assertNull(fixture.model.state.value.errorMessage)
        val replacement = MutableSharedFlow<MeshEvent>()
        fixture.events = replacement
        fixture.workspace.reattach()
        runCurrent()
        backgroundScope.launch { replacement.emit(response()) }
        runCurrent()
        assertEquals(1, fixture.model.state.value.results.size)
        assertEquals(1, fixture.sendCalls)
    }
}
