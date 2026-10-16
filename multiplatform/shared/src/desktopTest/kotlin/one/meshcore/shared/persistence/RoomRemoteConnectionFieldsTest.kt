package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.testSession
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomRemoteConnectionFieldsTest {
    private lateinit var file: File
    private lateinit var database: MeshDatabase
    private lateinit var store: RoomNodeStore
    private val saved = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE).copy(
        isPinned = true,
        unreadCount = 7,
        neighborCount = 3,
        lastSyncTimestamp = 100,
        lastConnectedDate = OLD_TIME,
        lastBatteryMillivolts = 3700,
        lastUptimeSeconds = 900,
        lastNoiseFloor = -110,
        lastRxAirtimeSeconds = 20,
        lastMessageDate = OLD_TIME,
    )

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("remote-connection-fields", ".db").also { it.delete() }
        database = meshDatabase(file.absolutePath)
        store = RoomNodeStore(
            saved.radioID,
            database.remoteNodeSessionDao(),
            database.roomMessageDao(),
            database.contactDao(),
        ) { NEW_TIME }
    }

    @AfterTest
    fun tearDown() {
        database.close()
        file.delete()
    }

    @Test
    fun aTransientDisconnectPreservesAuthenticationAndRoomMetadata() = runBlocking {
        store.saveSession(saved)
        store.markSessionDisconnected(saved.id)
        assertSaved(saved.copy(isConnected = false))
    }

    @Test
    fun loginAndLogoutPreserveRoomMetadataAndLogoutKeepsTheConnectedDate() = runBlocking {
        store.saveSession(saved)
        store.updateSessionConnection(saved.id, true, RoomPermissionLevel.ADMIN)
        val connected = saved.copy(permissionLevel = RoomPermissionLevel.ADMIN, lastConnectedDate = NEW_TIME)
        assertSaved(connected)
        store.updateSessionConnection(saved.id, false, RoomPermissionLevel.GUEST)
        assertSaved(connected.copy(isConnected = false, permissionLevel = RoomPermissionLevel.GUEST))
    }

    @Test
    fun disconnectKeepsANullConnectionDateAndMissingRowsRemainAbsent() = runBlocking {
        store.markSessionDisconnected(saved.id)
        store.updateSessionConnection(saved.id, true, RoomPermissionLevel.ADMIN)
        store.updateSessionConnection(saved.id, false, RoomPermissionLevel.GUEST)
        assertNull(store.fetchSessionById(saved.id))
        store.saveSession(saved.copy(lastConnectedDate = null))
        store.updateSessionConnection(saved.id, false, RoomPermissionLevel.GUEST)
        assertSaved(
            saved.copy(isConnected = false, permissionLevel = RoomPermissionLevel.GUEST, lastConnectedDate = null),
        )
    }

    private suspend fun assertSaved(expected: RemoteNodeSessionDto) {
        val actual = requireNotNull(store.fetchSessionById(expected.id))
        assertContentEquals(expected.publicKey, actual.publicKey)
        assertEquals(expected, actual.copy(publicKey = expected.publicKey))
    }

    private companion object {
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
    }
}
