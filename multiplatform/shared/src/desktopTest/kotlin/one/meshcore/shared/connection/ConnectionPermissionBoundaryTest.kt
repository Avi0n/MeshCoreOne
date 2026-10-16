package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionPermissionBoundaryTest {
    @Test
    fun deniedAdmissionDoesNotAllocateALinkSessionOrPartition() = runBlocking {
        val fixture = ConnectionPermissionBoundaryFixture()
        try {
            fixture.onManager {
                fixture.manager.disconnect()
                assertTrue(fixture.intent.restore().isUserDisconnected)
                fixture.authorized = false
                assertFailsWith<BluetoothUnauthorizedException> {
                    fixture.manager.connect(ConnectionPermissionBoundaryFixture.HANDLE)
                }
                assertTrue(fixture.bleLinks.isEmpty())
                assertTrue(fixture.database.deviceDao().listAll().isEmpty())
                assertEquals(0, fixture.mintedRadioIds)
                assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
                assertNull(fixture.manager.link)
                assertNull(fixture.manager.session)
                assertNull(fixture.manager.services)
                assertTrue(fixture.intent.restore().isUserDisconnected)
                assertFalse(fixture.manager.armBleReconnectIfWanted())
                assertEmptyQueues(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revocationAfterAdmissionRetiresOneAttemptWithoutRetrying() = runBlocking {
        val fixture = ConnectionPermissionBoundaryFixture()
        try {
            val operation = fixture.startRacedConnect()
            fixture.awaitRacedBoundary(operation)
            fixture.onManager {
                assertEquals(1, fixture.bleLinks.size)
                val attempted = fixture.bleLinks.single()
                assertEquals(1, attempted.connectCount)
                assertEquals(1, attempted.disconnectCount)
                assertTrue(operation.isCompleted)
                assertNotNull(operation.await())
                assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
                assertNull(fixture.manager.services)
                assertEquals(0, fixture.mintedRadioIds)
                assertFalse(fixture.manager.isBleReconnectInProgress())
                assertEmptyQueues(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aColdSavedRadioCanReconnectOnForegroundAfterTheGrantReturns() = runBlocking {
        val fixture = ConnectionPermissionBoundaryFixture()
        try {
            val saved = fixture.connectBleBaseline()
            fixture.replaceWithColdGraph()
            fixture.onManager {
                fixture.authorized = false
                val handle = assertNotNull(fixture.lastConnection.handle())
                assertFailsWith<BluetoothUnauthorizedException> { fixture.manager.activateLastBle(handle) }
                assertEquals(1, fixture.bleLinks.size)
                assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
                assertTrue(fixture.manager.wantsConnection)
                assertFalse(fixture.manager.armBleReconnectIfWanted())
                assertSavedIdentity(fixture, saved)
                assertEquals(
                    ConnectionPermissionBoundaryFixture.NOW_SECONDS,
                    fixture.lastConnection.bondVerificationEpochSeconds(handle),
                )
                fixture.authorized = true
                assertTrue(fixture.manager.armBleReconnectIfWanted())
            }
            val episode = fixture.onManager { assertNotNull(fixture.manager.reconnectJob) }
            withTimeout(ConnectionPermissionBoundaryFixture.TIMEOUT_MS) { episode.join() }
            fixture.onManager {
                assertEquals(
                    ConnectionState.Ready(ConnectionPermissionBoundaryFixture.HANDLE),
                    fixture.manager.state.value,
                )
                assertEquals(2, fixture.bleLinks.size)
                assertSavedIdentity(fixture, saved)
                assertEmptyQueues(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deniedBleAdmissionPreservesTheActiveWifiGraphAndSavedIdentity() = runBlocking {
        val fixture = ConnectionPermissionBoundaryFixture()
        try {
            fixture.connectWifi()
            fixture.onManager {
                val state = fixture.manager.state.value
                val link = fixture.manager.link
                val session = fixture.manager.session
                val services = assertNotNull(fixture.manager.services)
                assertTrue(state is ConnectionState.Ready)
                val saved = fixture.database.deviceDao().listAll().single()
                fixture.authorized = false
                assertFailsWith<BluetoothUnauthorizedException> {
                    fixture.manager.connect(ConnectionPermissionBoundaryFixture.HANDLE, forceReconnect = true)
                }
                assertEquals(state, fixture.manager.state.value)
                assertSame(link, fixture.manager.link)
                assertSame(session, fixture.manager.session)
                assertSame(services, fixture.manager.services)
                assertEquals(TransportType.WIFI, fixture.manager.currentTransportType)
                assertTrue(fixture.bleLinks.isEmpty())
                assertEquals(1, fixture.wifiLinks.single().connectCount)
                assertSavedIdentity(fixture, saved)
                assertEmptyQueues(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun olderUnauthorizedCleanupCannotStandDownANewerWifiConnection() = runBlocking {
        val fixture = ConnectionPermissionBoundaryFixture()
        try {
            fixture.holdDeniedDisconnect = true
            val operation = fixture.startRacedConnect()
            withTimeout(ConnectionPermissionBoundaryFixture.TIMEOUT_MS) { fixture.deniedCleanupEntered.await() }
            fixture.connectWifi()
            fixture.onManager {
                val state = fixture.manager.state.value
                val link = fixture.manager.link
                val session = fixture.manager.session
                val services = assertNotNull(fixture.manager.services)
                assertTrue(state is ConnectionState.Ready)
                fixture.releaseDeniedCleanup.complete(Unit)
                withTimeout(ConnectionPermissionBoundaryFixture.TIMEOUT_MS) { operation.await() }
                assertEquals(state, fixture.manager.state.value)
                assertSame(link, fixture.manager.link)
                assertSame(session, fixture.manager.session)
                assertSame(services, fixture.manager.services)
                assertEquals(TransportType.WIFI, fixture.manager.currentTransportType)
                assertEquals(1, fixture.bleLinks.single().connectCount)
                assertEquals(1, fixture.bleLinks.single().disconnectCount)
                assertEquals(1, fixture.wifiLinks.single().connectCount)
                assertEmptyQueues(fixture)
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertSavedIdentity(fixture: ConnectionPermissionBoundaryFixture, saved: DeviceEntity) {
        val current = fixture.database.deviceDao().listAll().single()
        assertEquals(saved.id, current.id)
        assertEquals(saved.radioID, current.radioID)
        assertContentEquals(saved.publicKey, current.publicKey)
        assertEquals(saved.id, fixture.lastConnection.deviceId())
        assertEquals(saved.radioID, fixture.lastConnection.radioId())
        assertEquals(1, fixture.mintedRadioIds)
        assertFalse(fixture.intent.restore().isUserDisconnected)
    }

    private suspend fun assertEmptyQueues(fixture: ConnectionPermissionBoundaryFixture) {
        assertTrue(fixture.database.messageDao().listAll().isEmpty())
        assertTrue(fixture.database.roomMessageDao().listAll().isEmpty())
        assertTrue(fixture.database.remoteNodeSessionDao().getAll().isEmpty())
        for (device in fixture.database.deviceDao().listAll()) {
            assertTrue(fixture.database.pendingSendDao().listByRadio(device.radioID).isEmpty())
        }
        assertTrue(fixture.database.pendingSendDao().listOrphans().isEmpty())
    }
}
