package one.meshcore.shared.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.contact.MAX_DISCOVERED_NODES_PER_RADIO
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.DiscoveredNodeEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.floodScope
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toEntity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Import-side reconciliation that the decode-only fixture tests cannot see: what the persisted rows
 * hold after an envelope lands. Covers the legacy flood-scope inference, the `lastMessageDate`
 * refresh, the source of channel capacity, and the Discover cap's drop reporting.
 */
class RoomBackupImportReconciliationTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("backup-reconcile-", ".db")
        dbFile.deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun legacyEnvelopeChannelKeepsItsPinnedRegionOnThePersistedRow() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        // The real pre-floodScopeModeRawValue envelope: regionScope present, mode key absent.
        val envelope = decodeBackupEnvelope(loadBackupFixture("legacy-minimal.json"))
        val source = envelope.channels.single()
        assertNull(source.floodScopeModeRawValue)
        assertEquals("Germany", source.regionScope)

        RoomBackupImporter.importEnvelope(db, envelope)

        val row = db.channelDao().listAll().single { it.name == "#ops" }
        // Storing "inherit" here would drop the pin and send the channel's broadcasts to the device
        // default region, and the next export would write that "inherit" back over the iOS setting.
        assertEquals(ChannelFloodScopeStorage.MODE_SPECIFIC, row.floodScopeModeRawValue)
        assertEquals("Germany", row.regionScope)
        assertEquals(ChannelFloodScope.Region("Germany"), row.floodScope)
        db.close()
    }

    @Test
    fun importAdvancesContactAndChannelLastMessageDateFromRestoredMessages() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000E1")
        val contactID = Uuid.normalized("C0000000-0000-0000-0000-0000000000E1")
        val newerLocalID = Uuid.normalized("C0000000-0000-0000-0000-0000000000E2")
        val newerKey = ByteArray(32) { 0x7E }
        db.contactDao().upsert(
            contactDto(newerLocalID, radio, newerKey).copy(lastMessageDate = NEWER_LOCAL_DATE).toEntity(),
        )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    contacts = listOf(
                        contactDto(contactID, radio, ByteArray(32) { 0x7D }),
                        contactDto(newerLocalID, radio, newerKey),
                    ),
                    channels = listOf(channelDto(radio, index = 0, secretByte = 0x3C)),
                    messages = listOf(
                        directMessage(Uuid.normalized("M0000000-0000-0000-0000-0000000000E1"), radio, contactID),
                        directMessage(
                            Uuid.normalized("M0000000-0000-0000-0000-0000000000E2"),
                            radio,
                            newerLocalID,
                        ).copy(deduplicationKey = "dm-older-2"),
                        channelMessage(Uuid.normalized("M0000000-0000-0000-0000-0000000000E3"), radio, index = 0),
                    ),
                ),
            )

        RoomBackupImporter.importEnvelope(db, envelope)

        // A null date hides the conversation from the list entirely; a stale one sorts it wrong.
        assertEquals(DM_DATE, db.contactDao().getById(contactID)!!.lastMessageDate)
        assertEquals(CHANNEL_DATE, db.channelDao().listByRadio(radio).single().lastMessageDate)
        // An already-newer local value is never walked backwards.
        assertEquals(NEWER_LOCAL_DATE, db.contactDao().getById(newerLocalID)!!.lastMessageDate)
        db.close()
    }

    @Test
    fun importAdvancesRemoteNodeSessionLastMessageDateFromRestoredRoomMessages() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000E3")
        val session = sessionDto(Uuid.normalized("50000000-0000-0000-0000-0000000000E3"), radio)
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    remoteNodeSessions = listOf(session),
                    roomMessages = listOf(
                        roomMessage(Uuid.normalized("RM000000-0000-0000-0000-0000000000E3"), session.id),
                    ),
                ),
            )

        RoomBackupImporter.importEnvelope(db, envelope)

        assertEquals(ROOM_DATE, db.remoteNodeSessionDao().getById(session.id)!!.lastMessageDate)
        db.close()
    }

    @Test
    fun channelCapacityComesFromTheEnvelopeNotAStaleLocalDeviceRow() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000E4")
        val publicKey = ByteArray(32) { 0x4E }
        // The local row still reports the two slots an older firmware exposed; the backup knows eight.
        seedDevice(db, radio, maxChannels = 2, publicKey = publicKey)
        seedChannel(db, radio, index = 0, secretByte = 0x11)
        seedChannel(db, radio, index = 1, secretByte = 0x22)

        val relocating = channelDto(radio, index = 1, secretByte = 0xAA).copy(name = "#relocated")
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(deviceDto(radio, publicKey, maxChannels = 8)),
                    channels = listOf(relocating),
                    messages = listOf(
                        channelMessage(Uuid.normalized("M0000000-0000-0000-0000-0000000000E4"), radio, index = 1),
                    ),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // Bounding placement by the stale local capacity would drop the channel and its history.
        assertEquals(1, result.channelsInserted)
        assertEquals(0, result.channelsDropped)
        assertEquals(0, result.messagesDropped)
        val placed = assertNotNull(db.channelDao().listByRadio(radio).firstOrNull { it.name == "#relocated" })
        assertEquals(2, placed.index)
        db.close()
    }

    @Test
    fun discoverRowsDroppedByTheCapAndRejectedRowsAreReported() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000E5")
        val cap = MAX_DISCOVERED_NODES_PER_RADIO
        fillDiscoverListToCap(db, radio, cap)

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    discoveredNodes = listOf(
                        discoveredDto(radio, index = cap + 1),
                        discoveredDto(radio, index = cap + 2).copy(publicKey = ByteArray(8) { 0x5A }),
                    ),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // Silently discarding these tells the user the import succeeded while Discover rows vanished.
        assertEquals(0, result.discoveredNodesInserted)
        assertEquals(1, result.discoveredNodesDropped)
        assertEquals(1, result.discoveredNodesSkipped)
        assertEquals(1, result.totalDropped)
        db.close()
    }

    private suspend fun fillDiscoverListToCap(db: MeshDatabase, radio: Uuid, cap: Int) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                for (index in 1..cap) {
                    db.discoveredNodeDao().upsert(
                        DiscoveredNodeEntity(
                            id = Uuid.normalized(indexedUuid("E0000000", index)),
                            radioID = radio,
                            publicKey = publicKeyForIndex(index),
                            name = "Node $index",
                            typeRawValue = 2,
                            lastHeardEpochSeconds = index.toLong(),
                            lastAdvertTimestamp = index.toLong(),
                            latitude = 0.0,
                            longitude = 0.0,
                            outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                            outPath = ByteArray(0),
                        ),
                    )
                }
            }
        }
    }

    private fun discoveredDto(radio: Uuid, index: Int): DiscoveredNodeDto = DiscoveredNodeDto(
        id = Uuid.normalized(indexedUuid("F0000000", index)),
        radioID = radio,
        publicKey = publicKeyForIndex(index),
        name = "Incoming $index",
        typeRawValue = 2,
        lastHeard = index.toDouble(),
        lastAdvertTimestamp = index.toLong(),
        latitude = 0.0,
        longitude = 0.0,
        outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
        outPath = ByteArray(0),
    )

    /** Distinct 32-byte key per index, so the `(radioID, publicKey)` dedup key never collides. */
    private fun publicKeyForIndex(index: Int): ByteArray = ByteArray(32) { position ->
        if (position == 0) (index and BYTE_MASK).toByte() else ((index shr Byte.SIZE_BITS) and BYTE_MASK).toByte()
    }

    private fun indexedUuid(prefix: String, index: Int): String =
        "$prefix-0000-0000-0000-${index.toString().padStart(12, '0')}"

    private fun contactDto(id: Uuid, radio: Uuid, publicKey: ByteArray): ContactDto = ContactDto(
        id = id,
        radioID = radio,
        publicKey = publicKey,
        name = "Alice",
        typeRawValue = 1,
        flags = 0,
        outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private fun channelDto(radio: Uuid, index: Int, secretByte: Int): ChannelDto = ChannelDto(
        id = Uuid.normalized(indexedUuid("C1000000", index + 1)),
        radioID = radio,
        index = index,
        name = "#ops",
        secret = ByteArray(ChannelSecret.SIZE_BYTES) { secretByte.toByte() },
        isEnabled = true,
        unreadCount = 0,
    )

    private fun sessionDto(id: Uuid, radio: Uuid): RemoteNodeSessionDto = RemoteNodeSessionDto(
        id = id,
        radioID = radio,
        publicKey = ByteArray(32) { 0x6E },
        name = "Room",
        role = RemoteNodeRole.ROOM_SERVER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = RoomPermissionLevel.READ_WRITE,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )

    private fun roomMessage(id: Uuid, sessionID: Uuid): RoomMessageDto = RoomMessageDto(
        id = id,
        sessionID = sessionID,
        authorKeyPrefix = byteArrayOf(1, 2, 3, 4),
        text = "hi room",
        timestamp = ROOM_DATE.toLong(),
        createdAt = ROOM_DATE,
        isFromSelf = false,
        deduplicationKey = "room-1",
        statusRawValue = MessageStatus.DELIVERED.raw,
        retryAttempt = 0,
        maxRetryAttempts = 0,
    )

    private fun directMessage(id: Uuid, radio: Uuid, contactID: Uuid): MessageDto =
        baseMessage(id, radio).copy(contactID = contactID, deduplicationKey = "dm-${id.value}")

    private fun channelMessage(id: Uuid, radio: Uuid, index: Int): MessageDto = baseMessage(id, radio).copy(
        timestamp = CHANNEL_DATE.toLong(),
        createdAt = CHANNEL_DATE,
        channelIndex = index,
        deduplicationKey = "ch-$index-1-n-abcd",
    )

    private fun baseMessage(id: Uuid, radio: Uuid): MessageDto = MessageDto(
        id = id,
        radioID = radio,
        text = "hello",
        timestamp = DM_DATE.toLong(),
        createdAt = DM_DATE,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.SENT,
        textType = TextType.PLAIN,
        pathLength = 0,
        isRead = true,
        heardRepeats = 0,
        sendCount = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = false,
    )

    private fun deviceDto(radio: Uuid, publicKey: ByteArray, maxChannels: Int): DeviceDto = DeviceDto(
        id = Uuid.normalized("D1000000-0000-0000-0000-${radio.value.takeLast(12)}"),
        radioID = radio,
        publicKey = publicKey,
        nodeName = "Node",
        firmwareVersion = 1,
        firmwareVersionString = "v1",
        manufacturerName = "m",
        buildDate = "d",
        maxContacts = 10,
        maxChannels = maxChannels,
        frequency = 915_000,
        bandwidth = 250_000,
        spreadingFactor = 10,
        codingRate = 5,
        txPower = 20,
        maxTxPower = 20,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = true,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
    )

    private suspend fun seedDevice(db: MeshDatabase, radio: Uuid, maxChannels: Int, publicKey: ByteArray) {
        val dto = deviceDto(radio, publicKey, maxChannels)
        db.deviceDao().upsert(
            DeviceEntity(
                id = dto.id,
                radioID = dto.radioID,
                publicKey = dto.publicKey,
                nodeName = dto.nodeName,
                firmwareVersion = dto.firmwareVersion,
                firmwareVersionString = dto.firmwareVersionString,
                manufacturerName = dto.manufacturerName,
                buildDate = dto.buildDate,
                maxContacts = dto.maxContacts,
                maxChannels = dto.maxChannels,
                frequency = dto.frequency,
                bandwidth = dto.bandwidth,
                spreadingFactor = dto.spreadingFactor,
                codingRate = dto.codingRate,
                txPower = dto.txPower,
                maxTxPower = dto.maxTxPower,
                latitude = dto.latitude,
                longitude = dto.longitude,
                blePin = dto.blePin,
                clientRepeat = dto.clientRepeat,
                pathHashMode = dto.pathHashMode,
                manualAddContacts = dto.manualAddContacts,
                autoAddConfig = dto.autoAddConfig,
                autoAddMaxHops = dto.autoAddMaxHops,
                multiAcks = dto.multiAcks,
                telemetryModeBase = dto.telemetryModeBase,
                telemetryModeLoc = dto.telemetryModeLoc,
                telemetryModeEnv = dto.telemetryModeEnv,
                advertLocationPolicy = dto.advertLocationPolicy,
                lastConnected = dto.lastConnected,
                lastContactSync = dto.lastContactSync,
                isActive = dto.isActive,
            ),
        )
    }

    private suspend fun seedChannel(db: MeshDatabase, radio: Uuid, index: Int, secretByte: Int) {
        db.channelDao().upsert(
            ChannelEntity(
                id = Uuid.normalized(indexedUuid("C2000000", index + 1)),
                radioID = radio,
                index = index,
                name = "local$index",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { secretByte.toByte() },
                isEnabled = true,
                unreadCount = 0,
            ),
        )
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val DM_DATE = 500.0
        const val CHANNEL_DATE = 700.0
        const val ROOM_DATE = 800.0
        const val NEWER_LOCAL_DATE = 900.0
    }
}
