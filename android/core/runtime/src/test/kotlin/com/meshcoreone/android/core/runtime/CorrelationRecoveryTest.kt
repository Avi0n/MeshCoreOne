// AndroidOnly: WP-207 Real protocol quarantine drives asynchronous generation-owned physical recovery.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

internal class RecoveryDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
    private var paused = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (paused) queued += context to block else delegate.dispatch(context, block)
    }
    val queuedCount: Int get() = queued.size
    fun open() {
        paused = false
        queued.toList().also { queued.clear() }.forEach { (context, block) -> delegate.dispatch(context, block) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withRecoveryFixture(
    assertions: suspend RuntimeFixture.(RecoveryDispatcher) -> Unit,
) {
    val dispatcher = RecoveryDispatcher(StandardTestDispatcher(testScheduler))
    val fixture = RuntimeFixture(this, recoveryDispatcher = dispatcher)
    try { fixture.assertions(dispatcher) }
    finally { dispatcher.open(); fixture.close() }
}

private fun channelPacket(index: Int = 0, name: String = "Fresh"): Bytes =
    Bytes.of(0x12, index) + Bytes.utf8(name).paddedOrTruncated(32) + Bytes(ByteArray(16))

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun RuntimeFixture.unmatchedChannelQuery() {
    val radio = radios.last()
    radio.channelResponse = { Bytes.of(1, 2) }
    val query = test.backgroundScope.async { runCatching { services.last().inputs.connection.session.getChannel(0u) } }
    test.runCurrent()
    assertEquals(Bytes.of(0x1f, 0), radio.frames.last())
    test.advanceTimeBy(999)
    test.runCurrent()
    assertFalse(query.isCompleted)
    test.advanceTimeBy(1)
    test.runCurrent()
    assertIs<MeshCoreException.Timeout>(query.await().exceptionOrNull())
}

@OptIn(ExperimentalCoroutinesApi::class)
class CorrelationRecoveryTest {
    @TestFactory
    fun recoveryCases() = listOf(
        nativeCase("recovery coalesces before accounting and keeps the live token until persistence completes") {
            withRecoveryFixture { recovery ->
                connect()
                val old = services.single()
                val snapshot = manager.snapshot.value
                val persisted = CompletableDeferred<Unit>()
                old.beforeChannelQueryAccounting = {
                    assertEquals(snapshot, manager.snapshot.value)
                    persisted.await()
                    assertEquals(snapshot.token, manager.snapshot.value.token)
                    assertEquals(0, old.teardowns)
                }
                radios.single().pipelinedReads = true
                radios.single().channelResponse = { null }
                val query = backgroundScope.async { old.inputs.connection.session.getChannels(listOf(0u, 1u, 2u)) }
                runCurrent(); advanceTimeBy(1_500); runCurrent()
                assertEquals(listOf<UByte>(0u, 1u, 2u), query.await().missing)
                recovery.open(); runCurrent()
                assertEquals(1, old.channelQueryAccountingCalls)
                assertEquals(snapshot, manager.snapshot.value)
                assertEquals(0, old.teardowns)
                assertEquals(0, radios.single().closes)
                assertEquals(0, lossCount)
                assertFalse(manager.isReconnectionWatchdogRunning)
                persisted.complete(Unit); runCurrent()
                assertNull(manager.snapshot.value.token)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, old.teardowns)
                assertEquals(1, radios.single().closes)
                assertEquals(1, lossCount)
                advanceTimeBy(30_000); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertTrue(services.last().token.generation.value > old.token.generation.value)
            }
        },
        nativeCase("manual stop cancels the accounting observation without waiting for or reviving the query") {
            withRecoveryFixture { recovery ->
                connect()
                val old = services.single()
                val observationCancelled = CompletableDeferred<Unit>()
                old.beforeChannelQueryAccounting = {
                    try { awaitCancellation() }
                    finally { observationCancelled.complete(Unit) }
                }
                unmatchedChannelQuery()
                recovery.open(); runCurrent()
                assertEquals(1, old.channelQueryAccountingCalls)
                manager.disconnect()
                runCurrent()
                assertTrue(observationCancelled.isCompleted)
                assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                assertNull(manager.snapshot.value.token)
                assertEquals(1, old.teardowns)
                assertEquals(1, radios.single().closes)
                assertEquals(0, lossCount)
                advanceTimeBy(120_000); runCurrent()
                assertEquals(1, radios.size)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("replacement cancels an old accounting observation before its successor becomes ready") {
            withRecoveryFixture { recovery ->
                connect()
                val old = services.single()
                val observationCancelled = CompletableDeferred<Unit>()
                old.beforeChannelQueryAccounting = {
                    try { awaitCancellation() }
                    finally { observationCancelled.complete(Unit) }
                }
                unmatchedChannelQuery()
                recovery.open(); runCurrent()
                assertEquals(1, old.channelQueryAccountingCalls)
                manager.connect(platform.target, forceFullSync = false, forceReconnect = true)
                runCurrent()
                assertFalse(observationCancelled.isCompleted)
                assertEquals(1, services.size)
                assertEquals(old.token, manager.snapshot.value.token)
                assertEquals(0, old.teardowns)
                assertEquals(0, radios.single().closes)

                val successorSyncStarted = CompletableDeferred<Unit>()
                onFactory = { successor, _ ->
                    successor.beforeSync = {
                        assertTrue(observationCancelled.isCompleted)
                        assertEquals(1, old.teardowns)
                        assertEquals(1, radios.first().closes)
                        assertNotEquals(DeviceConnectionState.READY, manager.connectionState)
                        successorSyncStarted.complete(Unit)
                    }
                }
                manager.connect(target(), forceFullSync = false, forceReconnect = true)
                runCurrent()
                assertTrue(observationCancelled.isCompleted)
                assertTrue(successorSyncStarted.isCompleted)
                assertEquals(2, services.size)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(services.last().token, manager.snapshot.value.token)
                assertTrue(services.last().token.generation.value > old.token.generation.value)
                assertEquals(1, old.teardowns)
                assertEquals(1, radios.first().closes)
                assertEquals(0, radios.last().closes)
                assertEquals(0, lossCount)
            }
        },
        nativeCase("cancelled query accounting cannot strand an otherwise live quarantined physical generation") {
            withRecoveryFixture { recovery ->
                connect()
                services.single().beforeChannelQueryAccounting = { throw CancellationException("admitted query cancelled") }
                unmatchedChannelQuery()
                recovery.open(); runCurrent()
                assertEquals(1, diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>()
                    .count { it.operation == "session.channelQueryAccounting.cancelled" })
                assertEquals(1, radios.single().closes)
                assertNull(manager.snapshot.value.token)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                advanceTimeBy(30_000); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("accounting failure is surfaced without leaving the quarantined physical generation alive") {
            withRecoveryFixture { recovery ->
                connect()
                val failure = IllegalStateException("terminal persistence failed")
                services.single().beforeChannelQueryAccounting = { throw failure }
                unmatchedChannelQuery()
                recovery.open(); runCurrent()
                val reported = diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>()
                    .single { it.operation == "session.channelQueryAccounting" }.cause
                assertEquals(failure.message, assertIs<IllegalStateException>(reported).message)
                val issue = assertIs<ConnectionIssue.Protocol>(manager.snapshot.value.issue)
                assertTrue(issue.error.suppressed.any { it === reported })
                assertEquals(1, radios.single().closes)
                assertNull(manager.snapshot.value.token)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("process shutdown cancels an accounting observation and closes its live graph exactly once") {
            val parent = Job()
            val fixture = RuntimeFixture(this, parent = parent)
            try {
                fixture.connect()
                val observed = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                fixture.services.single().beforeChannelQueryAccounting = {
                    observed.complete(Unit)
                    try { awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                }
                fixture.unmatchedChannelQuery()
                assertTrue(observed.isCompleted)
                parent.cancel(); runCurrent()
                fixture.manager.awaitShutdown()
                assertTrue(cancelled.isCompleted)
                assertEquals(1, fixture.radios.single().closes)
                assertEquals(1, fixture.services.single().teardowns)
                assertNull(fixture.manager.snapshot.value.token)
                assertEquals(0, fixture.lossCount)
            } finally { fixture.close(); parent.cancelAndJoin() }
        },
        nativeCase("unmatched channel error keeps quarantine then physically renews at the BLE watchdog boundary") {
            withRecoveryFixture { recovery ->
                connect()
                val old = services.single()
                val radio = radios.single()
                val states = mutableListOf<DeviceConnectionState>()
                val subscription = manager.subscribeTransitions()
                val collector = backgroundScope.launch { subscription.transitions.collect { states += it.state } }
                unmatchedChannelQuery()
                assertEquals(1, recovery.queuedCount)
                assertEquals(0, radio.closes)
                val fenced = assertFailsWith<MeshCoreException.ConnectionLost> { old.inputs.connection.session.getChannel(0u) }
                val unresolved = requiredCause<SessionCorrelationException.UnresolvedReply>(fenced)
                assertEquals("channel:0", unresolved.responseFamily)
                assertEquals(1, radio.frames.count { it[0].toInt() == 0x1f })
                recovery.open()
                runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertNull(manager.snapshot.value.token)
                assertNull(manager.connectedDevice)
                val issue = assertIs<ConnectionIssue.Protocol>(manager.snapshot.value.issue)
                assertEquals("channel:0", requiredCause<SessionCorrelationException.UnresolvedReply>(issue.error).responseFamily)
                assertEquals(1, radio.closes)
                assertEquals(0, radio.collectors)
                assertEquals(1, old.teardowns)
                assertTrue(old.ownedJobs.all(Job::isCompleted))
                assertEquals(1, links.single().callbackClosures)
                assertEquals(1, lossCount)
                assertTrue(manager.connectionIntent.wantsConnection)
                assertTrue(manager.isReconnectionWatchdogRunning)
                advanceTimeBy(29_999); runCurrent()
                assertEquals(1, radios.size)
                advanceTimeBy(1); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertTrue(services.last().token.generation.value > old.token.generation.value)
                assertEquals(old.token.radioId, services.last().token.radioId)
                assertEquals(1, services.last().monitoringStarts)
                assertEquals(1, radios.last().collectors)
                assertEquals(0, radios.last().closes)
                assertEquals("TestChannel", services.last().inputs.connection.session.getChannel(0u).name)
                assertEquals(listOf(
                    DeviceConnectionState.DISCONNECTED, DeviceConnectionState.CONNECTING,
                    DeviceConnectionState.CONNECTED, DeviceConnectionState.SYNCING, DeviceConnectionState.READY,
                ), states.filterIndexed { index, state -> index == 0 || states[index - 1] != state })
                subscription.close(); collector.cancelAndJoin()
            }
        },
        nativeCase("multiple real pipeline uncertainty diagnostics coalesce into one physical loss and recovery") {
            withRecoveryFixture { recovery ->
                connect()
                radios.single().pipelinedReads = true
                radios.single().channelResponse = { null }
                val query = backgroundScope.async { services.single().inputs.connection.session.getChannels(listOf(0u, 1u, 2u)) }
                runCurrent()
                advanceTimeBy(1_500); runCurrent()
                assertEquals(listOf<UByte>(0u, 1u, 2u), query.await().missing)
                assertEquals(3, recovery.queuedCount)
                recovery.open(); runCurrent()
                assertEquals(1, radios.single().closes)
                assertEquals(1, services.single().teardowns)
                assertEquals(1, lossCount)
                advanceTimeBy(30_000); runCurrent()
                assertEquals(2, radios.size)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(listOf(1, 1), services.map { it.monitoringStarts })
            }
        },
        nativeCase("late replies and retired callbacks cannot complete a fresh generation query or restart recovery") {
            withRecoveryFixture { recovery ->
                connect()
                val stale = links.single().callbacks!!
                val oldRadio = radios.single()
                unmatchedChannelQuery()
                oldRadio.receive(channelPacket(name = "Late"))
                recovery.open(); runCurrent()
                advanceTimeBy(30_000); runCurrent()
                val fresh = services.last()
                radios.last().channelResponse = { null }
                val query = backgroundScope.async { fresh.inputs.connection.session.getChannel(0u) }
                runCurrent()
                assertFailsWith<ClosedSendChannelException> { oldRadio.receive(channelPacket(name = "Retired")) }
                stale.onDisconnected(null); stale.onAutoReconnecting("retired"); stale.onReconnected()
                runCurrent()
                assertFalse(query.isCompleted)
                assertEquals(fresh.token, manager.snapshot.value.token)
                radios.last().receive(channelPacket(name = "Fresh"))
                runCurrent()
                assertEquals("Fresh", query.await().name)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, lossCount)
                assertEquals(0, radios.last().closes)
            }
        },
        nativeCase("manual stop before queued uncertainty wins without reviving intent or notifying loss") {
            withRecoveryFixture { recovery ->
                connect(); unmatchedChannelQuery()
                manager.disconnect()
                recovery.open(); runCurrent()
                advanceTimeBy(120_000); runCurrent()
                assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, radios.size)
                assertEquals(1, radios.single().closes)
                assertEquals(0, lossCount)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("manual stop during physical recovery joins the same close and suppresses all later retry work") {
            withRecoveryFixture { recovery ->
                connect()
                val closing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                radios.single().beforeClose = { closing.complete(Unit); release.await() }
                try {
                    unmatchedChannelQuery()
                    recovery.open(); runCurrent()
                    assertTrue(closing.isCompleted)
                    val stop = backgroundScope.async { manager.disconnect() }
                    runCurrent()
                    assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                    release.complete(Unit); runCurrent(); stop.await()
                    advanceTimeBy(120_000); runCurrent()
                    assertEquals(1, radios.size)
                    assertEquals(1, radios.single().closes)
                    assertEquals(1, services.single().teardowns)
                    assertEquals(0, lossCount)
                    assertFalse(manager.isReconnectionWatchdogRunning)
                } finally { release.complete(Unit) }
            }
        },
        nativeCase("written caller cancellation propagates while its reply owner times out before physical recovery") {
            withRecoveryFixture { recovery ->
                connect()
                radios.single().channelResponse = { null }
                val query = backgroundScope.async { services.single().inputs.connection.session.getChannel(0u) }
                runCurrent()
                query.cancelAndJoin()
                assertTrue(query.isCancelled)
                runCurrent()
                assertEquals(0, recovery.queuedCount)
                assertEquals(0, radios.single().closes)
                advanceTimeBy(999); runCurrent()
                assertEquals(0, recovery.queuedCount)
                advanceTimeBy(1); runCurrent()
                assertEquals(1, recovery.queuedCount)
                recovery.open(); runCurrent()
                assertEquals(1, radios.single().closes)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                advanceTimeBy(30_000); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("cancelling a queued unwritten query does not retire the healthy physical generation") {
            withRecoveryFixture { recovery ->
                connect()
                radios.single().channelResponse = { null }
                val session = services.single().inputs.connection.session
                val first = backgroundScope.async { session.getChannel(0u) }
                runCurrent()
                val queued = backgroundScope.async { session.getChannel(1u) }
                runCurrent(); queued.cancelAndJoin()
                radios.single().receive(channelPacket())
                runCurrent()
                assertEquals("Fresh", first.await().name)
                assertEquals(0, recovery.queuedCount)
                recovery.open(); runCurrent()
                assertEquals(0, radios.single().closes)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, radios.single().frames.count { it[0].toInt() == 0x1f })
            }
        },
        nativeCase("a queued diagnostic from a replaced graph cannot tear down its successor") {
            withRecoveryFixture { recovery ->
                connect(); unmatchedChannelQuery()
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                connect()
                val fresh = manager.snapshot.value
                recovery.open(); runCurrent()
                assertEquals(fresh, manager.snapshot.value)
                assertEquals(0, radios.last().closes)
                assertEquals(0, lossCount)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("WiFi uncertainty physically renews immediately but retains the source reconnect cooldown") {
            withRecoveryFixture { recovery ->
                connect(ConnectionTarget.WiFi("radio.test", 5_000u))
                unmatchedChannelQuery()
                recovery.open(); runCurrent()
                assertEquals(2, radios.size)
                assertEquals(1, radios.first().closes)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertFalse(manager.isReconnectionWatchdogRunning)
                unmatchedChannelQuery()
                runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(2, radios.size)
                assertEquals(listOf(1, 1), radios.map { it.closes })
                assertEquals(2, lossCount)
            }
        },
        nativeCase("process cancellation during a physical recovery finishes teardown once and never reconnects") {
            val parent = Job()
            val fixture = RuntimeFixture(this, parent = parent)
            val release = CompletableDeferred<Unit>()
            try {
                fixture.connect()
                fixture.radios.single().channelResponse = { null }
                val closing = CompletableDeferred<Unit>()
                fixture.radios.single().beforeClose = { closing.complete(Unit); release.await() }
                val query = backgroundScope.async { runCatching { fixture.services.single().inputs.connection.session.getChannel(0u) } }
                runCurrent(); advanceTimeBy(1_000); runCurrent()
                assertIs<MeshCoreException.Timeout>(query.await().exceptionOrNull())
                assertTrue(closing.isCompleted)
                parent.cancel()
                release.complete(Unit); runCurrent()
                fixture.manager.awaitShutdown()
                advanceTimeBy(120_000); runCurrent()
                assertEquals(1, fixture.radios.size)
                assertEquals(1, fixture.radios.single().closes)
                assertEquals(1, fixture.services.single().teardowns)
                assertEquals(0, fixture.radios.single().collectors)
                assertEquals(DeviceConnectionState.DISCONNECTED, fixture.manager.connectionState)
            } finally { release.complete(Unit); fixture.close(); parent.cancelAndJoin() }
        },
    )
}
