package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionWantedIntentTest {
    @Test
    fun aFreshInstallHasNoAutomaticConnectionIntent() = withFixture { fixture ->
        fixture.onManager { assertFalse(fixture.manager.isConnectionWanted()) }
    }

    @Test
    fun anOfflineBackupScopeDoesNotBecomeAConnectionTarget() = withFixture { fixture ->
        fixture.onManager {
            fixture.last.persistOfflineScopeIfAbsent(ConnectionWantedIntentFixture.RADIO, "Offline radio")
            assertFalse(fixture.manager.isConnectionWanted())
            assertEquals(ConnectionWantedIntentFixture.RADIO, fixture.last.radioId())
            assertEquals(null, fixture.last.deviceId())
        }
    }

    @Test
    fun aSavedDeviceCanBeRestoredWithoutAnExplicitIntentRecord() = withFixture { fixture ->
        fixture.onManager {
            fixture.seedSavedTarget()
            assertTrue(fixture.manager.isConnectionWanted())
        }
    }

    @Test
    fun explicitDisconnectVetoesASavedTarget() = withFixture { fixture ->
        fixture.onManager {
            fixture.seedSavedTarget()
            fixture.intent.persist(ConnectionIntent.UserDisconnected)
            assertFalse(fixture.manager.isConnectionWanted())
        }
    }

    @Test
    fun liveWantedIntentWinsBeforeATargetOrNewPersistedBitExists() = withFixture { fixture ->
        fixture.onManager {
            fixture.intent.persist(ConnectionIntent.UserDisconnected)
            assertFailsWith<BluetoothUnauthorizedException> {
                fixture.manager.connect(ConnectionWantedIntentFixture.HANDLE)
            }
            val reads = fixture.preferenceReads
            assertTrue(fixture.manager.isConnectionWanted())
            assertEquals(reads, fixture.preferenceReads)
            assertEquals(null, fixture.last.deviceId())
            assertTrue(fixture.intent.restore().isUserDisconnected)
        }
    }

    @Test
    fun liveWantedIntentDuringAnOldIntentReadWins() = liveWantedDuringRead(
        PersistenceKeys.USER_EXPLICITLY_DISCONNECTED,
    )

    @Test
    fun liveWantedIntentDuringAnOldTargetReadWins() = liveWantedDuringRead(
        PersistenceKeys.LAST_CONNECTED_DEVICE_ID,
    )

    @Test
    fun completedDisconnectDuringAnIntentReadRequiresFreshIntent() = disconnectDuringRead(
        PersistenceKeys.USER_EXPLICITLY_DISCONNECTED,
    )

    @Test
    fun completedDisconnectDuringATargetReadRequiresFreshIntent() = disconnectDuringRead(
        PersistenceKeys.LAST_CONNECTED_DEVICE_ID,
    )

    @Test
    fun targetReadCancellationPropagates() = failedRead(CancellationException("Synthetic target cancellation"))

    @Test
    fun targetReadFailureDoesNotBecomeFalseRetirementAuthority() = failedRead(
        IllegalStateException("Synthetic target read failure"),
    )

    @Test
    fun callerCancellationDuringATargetReadPropagates() = withFixture { fixture ->
        val query = fixture.startHeldQuery(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
        fixture.awaitRead()
        query.cancel()
        assertFailsWith<CancellationException> { query.await() }
        assertTrue(query.isCancelled)
    }

    @Test
    fun directStoreCancellationRetainsItsOriginalCause() = directReadFault(
        CancellationException("Synthetic direct cancellation"),
    )

    @Test
    fun directStoreFailureRetainsItsOriginalCause() = directReadFault(
        IllegalStateException("Synthetic direct failure"),
    )

    private fun directReadFault(error: Throwable) = withFixture { fixture ->
        val read = fixture.startHeldTargetRead()
        fixture.awaitRead()
        fixture.failRead(error)
        val actual = runCatching { read.await() }.exceptionOrNull()
        assertRecoveredFault(error, checkNotNull(actual))
        println("directStoreFault=${error::class.simpleName} copied=${actual !== error} originalCause=true")
    }

    private fun assertRecoveredFault(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        assertSame(expected, generateSequence(actual) { it.cause }.last())
    }

    private fun liveWantedDuringRead(key: String) = withFixture { fixture ->
        val query = fixture.startHeldQuery(key)
        fixture.awaitRead()
        fixture.onManager { fixture.manager.noteWantsConnection() }
        fixture.releaseRead()
        assertTrue(query.await())
    }

    private fun disconnectDuringRead(key: String) = withFixture { fixture ->
        fixture.onManager { fixture.seedSavedTarget() }
        val query = fixture.startHeldQuery(key)
        fixture.awaitRead()
        fixture.onManager { fixture.manager.disconnect() }
        fixture.releaseRead()
        assertFalse(query.await())
        fixture.onManager { assertTrue(fixture.intent.restore().isUserDisconnected) }
    }

    private fun failedRead(error: Throwable) = withFixture { fixture ->
        val query = fixture.startHeldQuery(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
        fixture.awaitRead()
        fixture.failRead(error)
        val actual = runCatching { query.await() }.exceptionOrNull()
        assertRecoveredFault(error, checkNotNull(actual))
    }

    private fun withFixture(block: suspend (ConnectionWantedIntentFixture) -> Unit) = runBlocking {
        val fixture = ConnectionWantedIntentFixture()
        try {
            withTimeout(ConnectionWantedIntentFixture.TIMEOUT_MS) { block(fixture) }
            fixture.onManager {
                assertEquals(0, fixture.linkAllocations)
                assertEquals(0, fixture.sessionAllocations)
                assertTrue(fixture.database.deviceDao().listAll().isEmpty())
            }
        } finally {
            fixture.close()
        }
    }
}
