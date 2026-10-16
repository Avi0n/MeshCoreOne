package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplicitDisconnectIntentTest {
    @Test
    fun persistedExplicitDisconnectWithoutLiveIntentSuppressesAutomaticServiceOwnership() = withFixture { fixture ->
        fixture.onManager {
            fixture.intent.persist(ConnectionIntent.UserDisconnected)

            assertTrue(fixture.manager.isExplicitlyDisconnected())
            assertFalse(fixture.manager.wantsConnection)
            assertEquals(ConnectionIntent.UserDisconnected, fixture.intent.restore())
        }
    }

    @Test
    fun anUnsetIntentIsNotAnExplicitDisconnect() = withFixture { fixture ->
        fixture.onManager {
            assertEquals(ConnectionIntent.None, fixture.intent.restore())
            assertFalse(fixture.manager.isExplicitlyDisconnected())
            assertFalse(fixture.manager.wantsConnection)
        }
    }

    @Test
    fun aDeniedExplicitConnectKeepsLiveWantedIntentWithoutChangingThePersistedBit() = withFixture { fixture ->
        fixture.onManager {
            fixture.intent.persist(ConnectionIntent.UserDisconnected)
            assertFailsWith<BluetoothUnauthorizedException> {
                fixture.manager.connect(ExplicitDisconnectIntentFixture.HANDLE)
            }

            assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
            assertTrue(fixture.manager.wantsConnection)
            assertEquals(ConnectionIntent.UserDisconnected, fixture.intent.restore())
            assertFalse(fixture.manager.isExplicitlyDisconnected())
        }
    }

    @Test
    fun wantedIntentEstablishedDuringACapturedPreferenceReadWinsOverTheOldBit() = withFixture { fixture ->
        fixture.onManager { fixture.intent.persist(ConnectionIntent.UserDisconnected) }
        val query = fixture.suspendNextIntentRead()
        fixture.awaitCapturedRead()
        fixture.onManager { fixture.manager.noteWantsConnection() }
        fixture.resumeIntentRead()

        assertFalse(query.await(), "the captured true preference must not override the new live wanted intent")
        fixture.onManager {
            assertTrue(fixture.manager.wantsConnection)
            assertEquals(ConnectionIntent.None, fixture.intent.restore())
        }
    }

    @Test
    fun aCompletedDisconnectDuringACapturedFalseReadRequiresFreshIntent() = withFixture { fixture ->
        fixture.onManager { fixture.intent.persist(ConnectionIntent.None) }
        val query = fixture.suspendNextIntentRead()
        fixture.awaitCapturedRead()
        fixture.onManager {
            fixture.manager.disconnect()
            assertFalse(fixture.manager.wantsConnection)
            assertEquals(ConnectionIntent.UserDisconnected, fixture.intent.restore())
        }
        fixture.resumeIntentRead()

        assertTrue(query.await(), "a completed disconnect must replace the captured false preference")
    }

    @Test
    fun cancellationFromASuspendedIntentReadPropagates() = withFixture { fixture ->
        val query = fixture.suspendNextIntentRead()
        fixture.awaitCapturedRead()
        try {
            fixture.cancelIntentRead()
            assertFailsWith<CancellationException> { query.await() }
        } finally {
            fixture.resumeIntentRead()
        }
    }

    private fun withFixture(block: suspend (ExplicitDisconnectIntentFixture) -> Unit) = runBlocking {
        val fixture = ExplicitDisconnectIntentFixture()
        try {
            withTimeout(ExplicitDisconnectIntentFixture.TIMEOUT_MS) { block(fixture) }
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
