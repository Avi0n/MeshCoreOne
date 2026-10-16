package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.DRAIN_TURNS
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.NEW_TIME
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.PASSWORD
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.owns
import one.meshcore.shared.remotenode.RemoteLoginSettlementFixture.Companion.wholeRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteLoginSettlementTest {
    private lateinit var fixture: RemoteLoginSettlementFixture

    @BeforeTest
    fun setUp() {
        fixture = RemoteLoginSettlementFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun retiringAConsumedLoginResultSettlesItsWaitingCaller() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare()
            fixture.start(setup)
            assertAtGate(setup)
            fixture.service.retireConnectionOperations()
            fixture.service.stopEventMonitoring()
            fixture.service.stopAllKeepAlives()
            fixture.monitor.join()
            fixture.timeout.join()
            repeat(DRAIN_TURNS) { yield() }

            assertTrue(fixture.monitor.isCancelled)
            assertTrue(fixture.monitor.isCompleted)
            assertTrue(fixture.timeout.isCompleted)
            assertOwnershipAndCommands(setup)
            assertEquals(wholeRows(setup.before), wholeRows(fixture.rows()))
            assertTrue(fixture.events.isEmpty())
            if (fixture.caller.isCompleted) {
                val result = assertNotNull(fixture.outcome)
                val error = result.exceptionOrNull()
                assertTrue(error is CancellationException || error === RemoteNodeError.Cancelled)
                assertFalse(result.isSuccess)
            }
            assertTrue(fixture.caller.isCompleted, "Consumed login caller must settle after its resolver jobs finish")
        }
    }

    @Test
    fun aLiveConsumedLoginResultAuthenticatesItsCallerAndRow() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare()
            fixture.start(setup)
            assertAtGate(setup)
            fixture.release.complete(Unit)
            val result = fixture.caller.await()
            fixture.eventReceived.await()
            assertTrue(result.success)
            assertTrue(result.isAdmin)
            assertEquals(RoomPermissionLevel.ADMIN, result.permissionLevel)
            assertContentEquals(setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH), result.publicKeyPrefix)
            assertTrue(fixture.caller.isCompleted)
            assertTrue(fixture.monitor.isActive)
            assertOwnershipAndCommands(setup)
            val expected = setup.before.map {
                if (it.id == setup.requested.id) {
                    it.copy(
                        isConnected = true,
                        permissionLevelRawValue = RoomPermissionLevel.ADMIN.raw,
                        lastConnectedDate = NEW_TIME,
                    )
                } else {
                    it
                }
            }
            assertEquals(wholeRows(expected), wholeRows(fixture.rows()))
            val expectedEvents = listOf<RemoteNodeEvent>(RemoteNodeEvent.SessionStateChanged(setup.requested.id, true))
            assertEquals(expectedEvents, fixture.events)
        }
    }

    private suspend fun assertAtGate(setup: RemoteLoginSettlementFixture.Setup) {
        val gate = fixture.entered.await()
        assertTrue(fixture.monitor.isActive)
        assertTrue(gate.operation.isActive)
        assertTrue(fixture.monitor.owns(gate.operation))
        assertNotSame(fixture.caller, gate.operation)
        assertTrue(fixture.caller.isActive)
        assertFalse(fixture.caller.isCompleted)
        assertEquals(null, fixture.outcome)
        assertEquals(setup.requested.id, fixture.pending.sessionID)
        assertTrue(fixture.pending.deferred.isActive)
        assertFalse(fixture.pending.deferred.isCompleted)
        assertTrue(fixture.timeout.isCancelled)
        assertSame(fixture.monitor, fixture.service.eventMonitorJob)
        val saved = setup.before.single { it.id == setup.requested.id }
        assertEquals(wholeRows(listOf(saved)), wholeRows(listOf(gate.row)))
        assertEquals(wholeRows(setup.before), wholeRows(fixture.rows()))
        assertTrue(fixture.events.isEmpty())
        assertOwnershipAndCommands(setup)
    }

    private suspend fun assertOwnershipAndCommands(setup: RemoteLoginSettlementFixture.Setup) {
        assertTrue(fixture.scope.coroutineContext.job.isActive)
        assertTrue(fixture.collectorActive())
        assertEquals(0, fixture.service.pendingLoginCount())
        fixture.service.mutex.withLock {
            assertTrue(fixture.service.pendingLogins.isEmpty())
            assertTrue(fixture.service.pendingLoginTimeoutJobs.isEmpty())
        }
        assertEquals(1, fixture.node.sendLoginCalls.size)
        assertContentEquals(setup.requested.publicKey, fixture.node.sendLoginCalls.single().first)
        assertEquals(PASSWORD, fixture.node.sendLoginCalls.single().second)
        assertTrue(fixture.node.sendLogoutCalls.isEmpty())
        assertTrue(fixture.node.sendKeepAliveCalls.isEmpty())
        assertTrue(fixture.node.sendCommandCalls.isEmpty())
        assertTrue(fixture.node.addContactCalls.isEmpty())
        assertTrue(fixture.node.getMessageCalls.isEmpty())
        assertTrue(fixture.node.resetPathCalls.isEmpty())
        assertTrue(fixture.node.requestStatusCalls.isEmpty())
        assertTrue(fixture.node.requestTelemetryCalls.isEmpty())
        assertTrue(fixture.node.requestOwnerInfoCalls.isEmpty())
        assertTrue(fixture.node.requestNeighboursCalls.isEmpty())
    }
}
