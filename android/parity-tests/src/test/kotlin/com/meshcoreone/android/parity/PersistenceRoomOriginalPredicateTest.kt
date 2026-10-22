// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceRoomOriginalPredicateTest : RoomProcessTest() {
    private suspend fun radio(): RadioId {
        val id = UUID.randomUUID()
        val device = DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
        store.saveDevice(device)
        return device.radioId
    }

    private suspend fun session(
        connected: Boolean? = null, permission: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    ): RemoteNodeSessionDTO {
        val source = RemoteNodeSessionDTO(
            radioId = radio(), publicKey = Bytes(ByteArray(32) { 0x55 }),
            name = "TestRoom", role = RemoteNodeRole.ROOM_SERVER,
            latitude = 37.7749, longitude = -122.4194,
        )
        val row = if (connected == null) source else source.copy(
            isConnected = connected, permissionLevel = permission, latitude = 0.0, longitude = 0.0,
        )
        store.saveRemoteNodeSessionDTO(row)
        return row
    }

    private fun key(session: RemoteNodeSessionDTO) = EntityKey(session.radioId, session.id)

    @OriginalCase("PersistenceStoreTests::Save and fetch remote node session()")
    @Test fun sessionReadbackRetainsTheOriginalNameRoleAndNonNullPredicates() = runTest {
        val session = session()
        val fetched = store.fetchRemoteNodeSession(key(session))
        assertNotNull(fetched)
        assertEquals("TestRoom", fetched.name)
        assertEquals(RemoteNodeRole.ROOM_SERVER, fetched.role)
    }

    @OriginalCase("PersistenceStoreTests::Update room activity advances sync timestamp and sets lastMessageDate()")
    @Test fun advancingActivityRetainsBothOriginalDatesAndTheMonotonicPredicate() = runTest {
        val session = session()
        store.updateRoomActivity(key(session), 1000u)
        var fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(1000u, fetched?.lastSyncTimestamp)
        val firstDate = assertNotNull(fetched?.lastMessageDate)

        store.updateRoomActivity(key(session), 2000u)
        fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(2000u, fetched?.lastSyncTimestamp)
        assertTrue(assertNotNull(fetched?.lastMessageDate) >= firstDate)
        assertNotNull(db.sessions().byId(session.radioId.value, session.id)?.lastMessageDate)
    }

    @OriginalCase("PersistenceStoreTests::Update room activity ignores older sync timestamps()")
    @Test fun olderActivityRetainsTheOriginalFiveThousandThenThreeThousandInputs() = runTest {
        val session = session()
        store.updateRoomActivity(key(session), 5000u)
        assertEquals(5000u, store.fetchRemoteNodeSession(key(session))?.lastSyncTimestamp)

        store.updateRoomActivity(key(session), 3000u)
        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(5000u, fetched?.lastSyncTimestamp)
        assertNotNull(fetched?.lastMessageDate)
    }

    @OriginalCase("PersistenceStoreTests::Update room activity without sync timestamp does not change lastSyncTimestamp()")
    @Test fun sendActivityRetainsTheOriginalFiveThousandBookmarkAndOmittedTimestamp() = runTest {
        val session = session()
        store.updateRoomActivity(key(session), 5000u)
        store.updateRoomActivity(key(session))

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(5000u, fetched?.lastSyncTimestamp)
        assertNotNull(fetched?.lastMessageDate)
    }

    @OriginalCase("PersistenceStoreTests::Mark room session connected changes isConnected and returns true()")
    @Test fun connectingRetainsOriginalDisconnectedAdminAndAllThreePredicates() = runTest {
        val session = session(false, RoomPermissionLevel.ADMIN)
        assertTrue(store.markRoomSessionConnected(key(session)))

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(true, fetched?.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, fetched?.permissionLevel)
    }

    @OriginalCase("PersistenceStoreTests::Mark room session connected returns false when already connected()")
    @Test fun alreadyConnectedRetainsTheOriginalGuestRatherThanAdminInput() = runTest {
        val session = session(true, RoomPermissionLevel.GUEST)
        assertFalse(store.markRoomSessionConnected(key(session)))
    }

    @OriginalCase("PersistenceStoreTests::Mark session disconnected preserves permission level()")
    @Test fun disconnectRetainsTheOriginalConnectedAdminSetupAndBothFinalFields() = runTest {
        val session = session(true, RoomPermissionLevel.ADMIN)
        store.markSessionDisconnected(key(session))

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(false, fetched?.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, fetched?.permissionLevel)
    }

    @OriginalCase("PersistenceStoreTests::Mark session disconnected is no-op when already disconnected()")
    @Test fun redundantDisconnectRetainsTheOriginalInitiallyDisconnectedAdmin() = runTest {
        val session = session(false, RoomPermissionLevel.ADMIN)
        store.markSessionDisconnected(key(session))

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(false, fetched?.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, fetched?.permissionLevel)
    }

    @OriginalCase("PersistenceStoreTests::Disconnect then recover preserves permission level()")
    @Test fun recoveryRetainsTheOriginalConnectedDisconnectReconnectSequence() = runTest {
        val session = session(true, RoomPermissionLevel.ADMIN)
        store.markSessionDisconnected(key(session))
        store.markRoomSessionConnected(key(session))

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(true, fetched?.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, fetched?.permissionLevel)
    }

    @OriginalCase("PersistenceStoreTests::Update remote node session connection can reset permission to guest()")
    @Test fun explicitLogoutRetainsBothOriginalDisconnectedAndGuestPredicates() = runTest {
        val session = session(true, RoomPermissionLevel.ADMIN)
        store.updateRemoteNodeSessionConnection(key(session), false, RoomPermissionLevel.GUEST)

        val fetched = store.fetchRemoteNodeSession(key(session))
        assertEquals(false, fetched?.isConnected)
        assertEquals(RoomPermissionLevel.GUEST, fetched?.permissionLevel)
    }

    @OriginalCase("PersistenceStoreTests::Save and fetch room messages()")
    @Test fun roomMessagesRetainOriginalAuthorsTextsWireTimesAndCount() = runTest {
        val session = session()
        for (index in 0 until 3) {
            store.saveRoomMessage(session.radioId, RoomMessageDTO(
                sessionID = session.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                authorName = "Author$index", text = "Room message $index",
                timestamp = Instant.now().epochSecond.toUInt() + index.toUInt(),
            ))
        }

        assertEquals(3, store.fetchRoomMessages(key(session)).size)
    }

    @OriginalCase("PersistenceStoreTests::Room messages tied on timestamp order deterministically by createdAt()")
    @Test fun tiedRoomWireTimesRetainTheOriginalWholeSecondArrivalInputs() = runTest {
        val session = session()
        val timestamp = Instant.now().epochSecond.toUInt()
        val base = Instant.ofEpochSecond(1_700_000_000)
        for ((text, offset) in listOf("second" to 1L, "third" to 2L, "first" to 0L)) {
            store.saveRoomMessage(session.radioId, RoomMessageDTO(
                sessionID = session.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                text = text, timestamp = timestamp, createdAt = base.plusSeconds(offset),
            ))
        }

        assertEquals(listOf("first", "second", "third"), store.fetchRoomMessages(key(session)).map { it.text })
    }

    @OriginalCase("PersistenceStoreTests::Room messages order primarily by wire timestamp()")
    @Test fun roomWireOrderRetainsTheOriginalCurrentClockAndOppositeArrivalOffsets() = runTest {
        val session = session()
        val timestamp = Instant.now().epochSecond.toUInt()
        val base = Instant.ofEpochSecond(1_700_000_000)
        for ((text, sendOffset, arrivalOffset) in listOf(
            Triple("newest", 2u, 0L), Triple("oldest", 0u, 2L), Triple("middle", 1u, 1L),
        )) {
            store.saveRoomMessage(session.radioId, RoomMessageDTO(
                sessionID = session.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4), text = text,
                timestamp = timestamp + sendOffset, createdAt = base.plusSeconds(arrivalOffset),
            ))
        }

        assertEquals(listOf("oldest", "middle", "newest"), store.fetchRoomMessages(key(session)).map { it.text })
    }

    @OriginalCase("PersistenceStoreTests::Room message deduplication()")
    @Test fun roomDedupRetainsTwoIndependentOriginalDtoConstructionsAndCount() = runTest {
        val session = session()
        val timestamp = Instant.now().epochSecond.toUInt()
        val author = Bytes.of(1, 2, 3, 4)
        store.saveRoomMessage(session.radioId, RoomMessageDTO(
            sessionID = session.id, authorKeyPrefix = author, text = "Duplicate message", timestamp = timestamp,
        ))
        store.saveRoomMessage(session.radioId, RoomMessageDTO(
            sessionID = session.id, authorKeyPrefix = author, text = "Duplicate message", timestamp = timestamp,
        ))

        assertEquals(1, store.fetchRoomMessages(key(session)).size)
    }

    @OriginalCase("PersistenceStoreTests::Cleanup duplicate remote node sessions keeps target and deletes others()")
    @Test fun duplicateCleanupRetainsOriginalDistinctNamesZeroCoordinatesAndMessage() = runTest {
        val radio = radio()
        val shared = Bytes(ByteArray(32) { 0x55 })
        val keep = RemoteNodeSessionDTO(
            radioId = radio, publicKey = shared, name = "KeepRoom", role = RemoteNodeRole.ROOM_SERVER,
        )
        val duplicate = RemoteNodeSessionDTO(
            radioId = radio, publicKey = shared, name = "DuplicateRoom", role = RemoteNodeRole.ROOM_SERVER,
        )
        store.saveRemoteNodeSessionDTO(keep)
        store.saveRemoteNodeSessionDTO(duplicate)
        store.saveRoomMessage(radio, RoomMessageDTO(
            sessionID = duplicate.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
            authorName = "Author", text = "Message on duplicate", timestamp = Instant.now().epochSecond.toUInt(),
        ))
        store.cleanupDuplicateRemoteNodeSessions(shared, key(keep))

        val kept = store.fetchRemoteNodeSession(key(keep))
        assertNotNull(kept)
        assertEquals("KeepRoom", kept.name)
        assertNull(store.fetchRemoteNodeSession(key(duplicate)))
        assertTrue(store.fetchRoomMessages(key(duplicate)).isEmpty())
    }
}
