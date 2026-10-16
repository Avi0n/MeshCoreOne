package one.meshcore.shared.connection

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.DeviceEntity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionPermissionFailureTest {
    @Test
    fun aDeniedReconnectTerminatesWithoutAllocatingAnotherLinkOrDiscardingSavedIdentity() = runBlocking {
        val fixture = ConnectionPermissionFailureFixture()
        try {
            val saved = fixture.connectBaseline()
            assertEquals(ConnectionState.Ready(ConnectionPermissionFailureFixture.HANDLE), fixture.manager.state.value)
            val episode = fixture.startDeniedReconnect()
            fixture.awaitDeniedRetirement()
            fixture.awaitBoundedEpisodeBoundary(episode)

            fixture.onManager {
                assertSavedState(fixture, saved)
                assertEquals(1, fixture.deniedLinks.size, "A denied permission must not allocate another BLE link")
                assertEquals(1, fixture.deniedLinks.single().connectCount)
                assertEquals(1, fixture.deniedLinks.single().disconnectCount)
                assertEquals(2, fixture.sessionAllocations, "Only the initial and single denied link may own sessions")
                assertTrue(episode.isCompleted, "The denied reconnect episode must terminate")
                assertFalse(fixture.manager.isBleReconnectInProgress())
                assertFalse(fixture.manager.state.value.isLinkUp)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anExplicitConnectAfterGrantReusesTheSavedPartitionAndPublicKey() = runBlocking {
        val fixture = ConnectionPermissionFailureFixture()
        try {
            val saved = fixture.connectBaseline()
            val episode = fixture.startDeniedReconnect()
            fixture.awaitDeniedRetirement()
            withTimeout(ConnectionPermissionFailureFixture.TIMEOUT_MS) { episode.cancelAndJoin() }
            fixture.grantAndConnect()

            fixture.onManager {
                assertEquals(
                    ConnectionState.Ready(ConnectionPermissionFailureFixture.HANDLE),
                    fixture.manager.state.value,
                )
                assertNotNull(fixture.manager.services)
                assertEquals(saved.radioID, fixture.manager.radioId)
                assertSavedState(fixture, saved)
                assertEquals(fixture.deniedLinks.size + 2, fixture.sessionAllocations)
                assertTrue(episode.isCompleted)
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertSavedState(fixture: ConnectionPermissionFailureFixture, saved: DeviceEntity) {
        val current = fixture.database.deviceDao().listAll().single()
        assertEquals(saved.id, current.id)
        assertEquals(saved.radioID, current.radioID)
        assertContentEquals(saved.publicKey, current.publicKey)
        assertEquals(1, fixture.mintedRadioIds)
        assertEquals(saved.id, fixture.lastConnection.deviceId())
        assertEquals(saved.radioID, fixture.lastConnection.radioId())
        assertEquals(ConnectionPermissionFailureFixture.HANDLE, fixture.lastConnection.handle())
        assertEquals(ConnectionPermissionFailureFixture.HANDLE, fixture.lastConnection.bondVerifiedHandle())
        assertEquals(
            ConnectionPermissionFailureFixture.NOW_SECONDS,
            fixture.lastConnection.bondVerificationEpochSeconds(ConnectionPermissionFailureFixture.HANDLE),
        )
        assertTrue(fixture.manager.wantsConnection)
        assertFalse(fixture.intent.restore() is ConnectionIntent.UserDisconnected)
        assertTrue(fixture.database.pendingSendDao().listByRadio(saved.radioID).isEmpty())
        assertTrue(fixture.database.messageDao().listAll().isEmpty())
        assertTrue(fixture.database.roomMessageDao().listAll().isEmpty())
        assertTrue(fixture.database.remoteNodeSessionDao().getAll().isEmpty())
    }
}
