package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.FIRST_SEND
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.NEW_TIME
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.PASSWORD
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.SECOND_SEND
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.owns
import one.meshcore.shared.remotenode.RemoteLoginPrefixFixture.Companion.wholeRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLoginPrefixTest {
    private lateinit var fixture: RemoteLoginPrefixFixture

    @BeforeTest
    fun setUp() {
        fixture = RemoteLoginPrefixFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun oldCallerCancellationPreservesANewerActualRegistration() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare()
            val old = startOld(setup)
            fixture.oldCaller.cancel(CancellationException("old caller canceled"))
            assertTrue(fixture.oldCaller.isCancelled)
            assertFalse(fixture.oldCaller.isCompleted)
            assertTrue(old.pending.deferred.isActive)
            val fresh = startReplacement(setup, old)
            fixture.oldScheduler.runCurrent()
            fixture.oldCaller.join()
            old.timer.join()
            fixture.barrier()
            assertTrue(fixture.oldCaller.isCompleted)
            assertTrue(fixture.oldCaller.isCancelled)
            assertIs<CancellationException>(assertNotNull(fixture.oldOutcome).exceptionOrNull())
            assertQuietAndLive(setup, SECOND_SEND)
            assertFreshSurvives(setup, fresh)
            completeFresh(setup, fresh)
        }
    }

    @Test
    fun currentCallerCancellationClearsItsOwnRequestAndAllowsANewLogin() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare()
            val old = startOld(setup)
            fixture.oldCaller.cancel(CancellationException("current caller canceled"))
            fixture.oldScheduler.runCurrent()
            fixture.oldCaller.join()
            old.timer.join()
            fixture.barrier()
            assertTrue(fixture.oldCaller.isCompleted)
            assertTrue(fixture.oldCaller.isCancelled)
            assertIs<CancellationException>(assertNotNull(fixture.oldOutcome).exceptionOrNull())
            assertSame(RemoteNodeError.Cancelled, old.pending.deferred.getCompletionExceptionOrNull())
            assertMapsEmpty()
            assertQuietAndLive(setup, FIRST_SEND)
            val fresh = fixture.startNew(setup)
            assertRegistration(fresh, setup)
            assertFreshSurvives(setup, fresh)
            completeFresh(setup, fresh)
        }
    }

    @Test
    fun ordinaryDuplicateReplacementStillRejectsTheOldRequestAndAuthenticatesTheNewOne() =
        runBlocking(fixture.dispatcher) {
            withTimeout(TIMEOUT_MS) {
                val setup = fixture.prepare()
                val old = startOld(setup)
                val fresh = startReplacement(setup, old)
                fixture.oldScheduler.runCurrent()
                fixture.oldCaller.join()
                old.timer.join()
                fixture.barrier()
                assertTrue(fixture.oldCaller.isCompleted)
                assertSame(RemoteNodeError.Cancelled, assertNotNull(fixture.oldOutcome).exceptionOrNull())
                assertQuietAndLive(setup, SECOND_SEND)
                assertFreshSurvives(setup, fresh)
                completeFresh(setup, fresh)
            }
        }

    private suspend fun startOld(setup: RemoteLoginPrefixFixture.Setup): RemoteLoginPrefixFixture.Registration {
        val old = fixture.startOld(setup)
        assertTrue(fixture.oldCaller.isActive)
        assertFalse(fixture.oldCaller.isCompleted)
        assertEquals(null, fixture.oldOutcome)
        assertTrue(fixture.oldCaller.owns(assertNotNull(fixture.initialReadOwner)))
        val read = assertNotNull(fixture.initialRead.await())
        assertEquals(setup.requested.id, read.id)
        assertEquals(setup.requested.radioID, read.radioID)
        assertContentEquals(setup.requested.publicKey, read.publicKey)
        assertRegistration(old, setup)
        fixture.service.mutex.withLock {
            val prefix = setup.requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
            assertSame(old.pending, fixture.service.pendingLogins[prefix])
            assertSame(old.timer, fixture.service.pendingLoginTimeoutJobs[prefix])
        }
        assertQuietAndLive(setup, FIRST_SEND)
        return old
    }

    private suspend fun startReplacement(
        setup: RemoteLoginPrefixFixture.Setup,
        old: RemoteLoginPrefixFixture.Registration,
    ): RemoteLoginPrefixFixture.Registration {
        val fresh = fixture.startNew(setup)
        old.timer.join()
        assertNotSame(old.pending, fresh.pending)
        assertNotSame(old.pending.deferred, fresh.pending.deferred)
        assertNotSame(old.timer, fresh.timer)
        assertTrue(old.timer.isCancelled)
        assertTrue(old.timer.isCompleted)
        assertSame(RemoteNodeError.Cancelled, old.pending.deferred.getCompletionExceptionOrNull())
        assertRegistration(fresh, setup)
        assertTrue(fixture.newCaller.isActive)
        assertFalse(fixture.newCaller.isCompleted)
        assertEquals(null, fixture.newOutcome)
        assertQuietAndLive(setup, SECOND_SEND)
        assertFreshSurvives(setup, fresh)
        return fresh
    }

    private fun assertRegistration(
        registration: RemoteLoginPrefixFixture.Registration,
        setup: RemoteLoginPrefixFixture.Setup,
    ) {
        assertEquals(setup.requested.id, registration.pending.sessionID)
        assertTrue(registration.pending.deferred.isActive)
        assertTrue(registration.timer.isActive)
        assertTrue(registration.timer.owns(registration.sender))
    }

    private suspend fun assertFreshSurvives(
        setup: RemoteLoginPrefixFixture.Setup,
        fresh: RemoteLoginPrefixFixture.Registration,
    ) {
        val current = fixture.service.mutex.withLock {
            val prefix = setup.requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
            listOf(
                fixture.service.pendingLogins[prefix] === fresh.pending,
                fixture.service.pendingLoginTimeoutJobs[prefix] === fresh.timer,
                fresh.pending.deferred.isActive,
                fresh.timer.isActive,
                fixture.newCaller.isActive,
                !fixture.newCaller.isCompleted,
            )
        }
        assertEquals(List(current.size) { true }, current, "Older cleanup must preserve the exact newer registration")
    }

    private suspend fun completeFresh(
        setup: RemoteLoginPrefixFixture.Setup,
        fresh: RemoteLoginPrefixFixture.Registration,
    ) {
        fixture.node.emitLoginSuccess(testLoginInfo(setup.requested.publicKey))
        val result = fixture.newCaller.await()
        fixture.eventReceived.await()
        fresh.timer.join()
        assertTrue(result.success)
        assertTrue(result.isAdmin)
        assertEquals(RoomPermissionLevel.ADMIN, result.permissionLevel)
        assertContentEquals(setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH), result.publicKeyPrefix)
        assertTrue(fixture.newCaller.isCompleted)
        assertMapsEmpty()
        assertGraphLive()
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
        assertCommands(setup, SECOND_SEND)
    }

    private suspend fun assertQuietAndLive(setup: RemoteLoginPrefixFixture.Setup, sends: Int) {
        assertEquals(wholeRows(setup.before), wholeRows(fixture.rows()))
        assertTrue(fixture.events.isEmpty())
        assertGraphLive()
        assertCommands(setup, sends)
    }

    private fun assertGraphLive() {
        assertTrue(fixture.scope.coroutineContext.job.isActive)
        assertTrue(fixture.oldScope.coroutineContext.job.isActive)
        assertTrue(fixture.monitor.isActive)
        assertTrue(fixture.collectorActive())
    }

    private suspend fun assertMapsEmpty() = fixture.service.mutex.withLock {
        assertTrue(fixture.service.pendingLogins.isEmpty())
        assertTrue(fixture.service.pendingLoginTimeoutJobs.isEmpty())
    }

    private fun assertCommands(setup: RemoteLoginPrefixFixture.Setup, sends: Int) {
        assertEquals(sends, fixture.node.sendLoginCalls.size)
        fixture.node.sendLoginCalls.forEach {
            assertContentEquals(setup.requested.publicKey, it.first)
            assertEquals(PASSWORD, it.second)
        }
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
