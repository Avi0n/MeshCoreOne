package one.meshcore.shared.connection

import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.remotenode.RemoteNodeStore
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid

internal class ConnectionSyncSuppressionRows(private val database: MeshDatabase) {
    suspend fun snapshot() = listOf(
        database.deviceDao().listAll().sortedBy { it.id.value }.map {
            it.copy(publicKey = EMPTY) to it.publicKey.toList()
        },
        database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }.map {
            it.copy(publicKey = EMPTY) to it.publicKey.toList()
        },
        database.contactDao().listAll().sortedBy { it.id.value }.map {
            listOf(
                it.copy(publicKey = EMPTY, outPath = EMPTY, avatarImageData = null),
                it.publicKey.toList(),
                it.outPath.toList(),
                it.avatarImageData?.toList(),
            )
        },
        database.channelDao().listAll().sortedBy { it.id.value }.map { it.copy(secret = EMPTY) to it.secret.toList() },
    )

    suspend fun seed(store: RemoteNodeStore, radioId: Uuid) {
        store.saveSession(
            testSession(
                radioID = radioId,
                permissionLevel = RoomPermissionLevel.READ_WRITE,
                lastSyncTimestamp = SYNC_TIME,
            ).copy(
                latitude = LATITUDE,
                longitude = LONGITUDE,
                unreadCount = UNREAD,
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
                isPinned = true,
                neighborCount = NEIGHBORS,
                lastConnectedDate = NOW,
                lastBatteryMillivolts = BATTERY,
                lastUptimeSeconds = UPTIME,
                lastNoiseFloor = NOISE_FLOOR,
                lastRxAirtimeSeconds = RX_AIRTIME,
                lastMessageDate = NOW,
            ),
        )
        store.saveSession(testSession(id = FOREIGN_ID, radioID = FOREIGN_RADIO, publicKey = testPublicKey(FOREIGN_KEY)))
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val NOW = 1_700_000_000.0
        private val FOREIGN_ID = testUuid(2)
        val FOREIGN_RADIO = testUuid(98)
        private const val FOREIGN_KEY = 67
        private const val SYNC_TIME = 123L
        private const val LATITUDE = 51.5
        private const val LONGITUDE = -0.1
        private const val UNREAD = 7
        private const val NEIGHBORS = 4
        private const val BATTERY = 3800
        private const val UPTIME = 1000L
        private const val NOISE_FLOOR = -120
        private const val RX_AIRTIME = 8L
    }
}
