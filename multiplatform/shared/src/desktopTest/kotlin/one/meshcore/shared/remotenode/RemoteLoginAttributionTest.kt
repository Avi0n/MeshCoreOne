package one.meshcore.shared.remotenode

import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Case
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Companion.FAILURE_REASON
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Companion.NEW_TIME
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Companion.PASSWORD
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.remotenode.RemoteLoginAttributionFixture.Companion.wholeRows
import one.meshcore.shared.settings.CommandAuditLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteLoginAttributionTest {
    private lateinit var fixture: RemoteLoginAttributionFixture

    @BeforeTest
    fun setUp() {
        fixture = RemoteLoginAttributionFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun aSharedPrefixSuccessAuthenticatesOnlyTheRequestedSession() = success(Case.SAME_RADIO_PREFIX)

    @Test
    fun aSharedKeyAcrossRadiosAuthenticatesOnlyTheRequestedPartition() = success(Case.CROSS_RADIO_KEY)

    @Test
    fun aUniquePrefixSuccessStillAuthenticatesItsRequestedSession() = success(Case.UNIQUE_PREFIX)

    @Test
    fun aSharedPrefixFailureAuditsTheRequestedRoleWithoutChangingRows() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare(Case.SAME_RADIO_PREFIX)
            assertSetup(setup)
            fixture.node.onSendLogin = {
                assertPending(setup)
                fixture.node.emitLoginFailed(setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH))
            }
            val caller = fixture.scope.async { fixture.service.login(setup.requested.id, PASSWORD) }
            try {
                val failure = runCatching { caller.await() }.exceptionOrNull()
                assertIs<RemoteNodeError.LoginFailed>(failure)
                assertEquals(FAILURE_REASON, failure.reason)
                assertCompleted(setup)
                assertTrue(caller.isCompleted)
                assertTrue(fixture.events.isEmpty())
                val rows = fixture.rows()
                assertEquals(wholeRows(setup.before), wholeRows(rows))
                assertEquals(2, fixture.audit.size)
                val target = if (setup.requested.role == RemoteNodeRole.ROOM_SERVER) {
                    CommandAuditLogger.Target.ROOM
                } else {
                    CommandAuditLogger.Target.REPEATER
                }
                val expected = CommandAuditLogger.Formats.loginFailed(
                    target,
                    setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH),
                    FAILURE_REASON,
                )
                assertEquals(expected, fixture.audit.last().message)
            } finally {
                caller.cancel()
                caller.join()
            }
        }
    }

    private fun success(kind: Case) = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val setup = fixture.prepare(kind)
            assertSetup(setup)
            fixture.node.onSendLogin = {
                assertPending(setup)
                fixture.node.emitLoginSuccess(testLoginInfo(setup.requested.publicKey))
            }
            val caller = fixture.scope.async { fixture.service.login(setup.requested.id, PASSWORD) }
            try {
                val result = caller.await()
                fixture.eventReceived.await()
                assertTrue(result.success)
                assertTrue(result.isAdmin)
                assertEquals(RoomPermissionLevel.ADMIN, result.permissionLevel)
                assertContentEquals(setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH), result.publicKeyPrefix)
                assertTrue(caller.isCompleted)
                assertCompleted(setup)
                assertEquals(2, fixture.audit.size)
                val actual = fixture.rows()
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
                val expectedEvents = listOf(RemoteNodeEvent.SessionStateChanged(setup.requested.id, true))
                assertEquals(wholeRows(expected) to expectedEvents, wholeRows(actual) to fixture.events.toList())
            } finally {
                caller.cancel()
                caller.join()
            }
        }
    }

    private suspend fun assertPending(setup: RemoteLoginAttributionFixture.Setup) {
        assertEquals(1, fixture.node.sendLoginCalls.size)
        assertContentEquals(setup.requested.publicKey, fixture.node.sendLoginCalls.single().first)
        assertEquals(PASSWORD, fixture.node.sendLoginCalls.single().second)
        fixture.service.mutex.withLock {
            assertNotNull(fixture.service.pendingLogins[setup.requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)])
            assertEquals(1, fixture.service.pendingLogins.size)
        }
    }

    private suspend fun assertCompleted(setup: RemoteLoginAttributionFixture.Setup) {
        assertEquals(1, fixture.node.sendLoginCalls.size)
        assertContentEquals(setup.requested.publicKey, fixture.node.sendLoginCalls.single().first)
        assertEquals(0, fixture.service.pendingLoginCount())
        fixture.service.mutex.withLock { assertTrue(fixture.service.pendingLoginTimeoutJobs.isEmpty()) }
        assertTrue(fixture.scope.coroutineContext.job.isActive)
        assertTrue(requireNotNull(fixture.service.eventMonitorJob).isActive)
        assertTrue(fixture.collectorActive())
        assertTrue(fixture.node.sendLogoutCalls.isEmpty())
        assertTrue(fixture.node.sendKeepAliveCalls.isEmpty())
        assertTrue(fixture.node.sendCommandCalls.isEmpty())
        assertTrue(fixture.node.addContactCalls.isEmpty())
        assertTrue(fixture.node.getMessageCalls.isEmpty())
    }

    private fun assertSetup(setup: RemoteLoginAttributionFixture.Setup) {
        assertEquals(setup.requested.radioID, setup.currentRadioID)
        assertNotEquals(setup.requested.id, setup.decoy.id)
        assertTrue(setup.before.all { !it.isConnected })
        assertTrue(setup.before.all { it.permissionLevelRawValue == RoomPermissionLevel.READ_WRITE.raw })
        assertEquals(3, setup.before.size)
        if (setup.kind == Case.UNIQUE_PREFIX) {
            assertFalse(
                setup.requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH) ==
                    setup.decoy.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH),
            )
        } else {
            assertEquals(setup.decoy.id, setup.selectedID)
            assertContentEquals(
                setup.requested.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH),
                setup.decoy.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH),
            )
        }
        if (setup.kind == Case.CROSS_RADIO_KEY) {
            assertNotEquals(setup.requested.radioID, setup.decoy.radioID)
            assertContentEquals(setup.requested.publicKey, setup.decoy.publicKey)
        } else {
            assertEquals(setup.requested.radioID, setup.decoy.radioID)
            assertFalse(setup.requested.publicKey.contentEquals(setup.decoy.publicKey))
        }
    }
}
