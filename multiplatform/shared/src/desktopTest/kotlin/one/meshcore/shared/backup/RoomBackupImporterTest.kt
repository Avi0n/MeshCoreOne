package one.meshcore.shared.backup

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomBackupImporterTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("backup-import-", ".db")
        dbFile.deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun importCountsDuplicateDevicePublicKeysAsSkipped() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radioA = Uuid.normalized("A0000000-0000-0000-0000-00000000000A")
        val radioB = Uuid.normalized("A0000000-0000-0000-0000-00000000000B")
        val key = ByteArray(32) { 0x11 }
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radioA, key), sampleDevice(radioB, key)),
                ),
            )
        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(1, result.devicesSkipped)
        assertEquals(1, result.devicesInserted)
        assertEquals(1, result.totalSkipped)
        db.close()
    }

    @Test
    fun importMergesContactMetadataWithoutUnblocking() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val publicKey = ByteArray(32) { 0xAB.toByte() }
        db.contactDao().upsert(
            ContactEntity(
                id = contactId,
                radioID = radio,
                publicKey = publicKey,
                name = "Alice",
                typeRawValue = 1,
                flags = 0,
                outPathLength = 255,
                outPath = ByteArray(0),
                lastAdvertTimestamp = 0,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = 0,
                isBlocked = true,
                isMuted = false,
                isFavorite = false,
                unreadCount = 1,
                unreadMentionCount = 0,
                nickname = null,
                lastMessageDate = null,
            ),
        )

        val foreign =
            ContactDto(
                id = contactId,
                radioID = radio,
                publicKey = publicKey,
                name = "Alice",
                typeRawValue = 1,
                flags = 0,
                outPathLength = 255,
                outPath = ByteArray(0),
                lastAdvertTimestamp = 0,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = 0,
                isBlocked = false,
                isMuted = true,
                isFavorite = true,
                unreadCount = 5,
                unreadMentionCount = 2,
                nickname = "Field",
                lastMessageDate = 100.0,
                avatarImageData = ByteArray(8) { 0x5A },
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    contacts = listOf(foreign),
                ),
            )
        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(0, result.contactsInserted)
        assertEquals(1, result.contactsSkipped)
        assertEquals(1, result.contactsMerged)
        assertEquals(1, result.contactsSkipped)
        val row = db.contactDao().getById(contactId)!!
        assertTrue(row.isBlocked)
        assertTrue(row.isMuted)
        assertTrue(row.isFavorite)
        assertEquals("Field", row.nickname)
        assertEquals(5, row.unreadCount)
        assertTrue(row.avatarImageData!!.contentEquals(ByteArray(8) { 0x5A }))
        db.close()
    }

    @Test
    fun importRemapsDirectHistoryOntoMergedLocalContact() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
        val localContactId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val foreignContactId = Uuid.normalized("C0000000-0000-0000-0000-000000000002")
        val publicKey = ByteArray(32) { 0xCD.toByte() }
        db.contactDao().upsert(sampleContact(localContactId, radio, publicKey).toEntity())

        val foreignContact = sampleContact(foreignContactId, radio, publicKey)
        val foreignMessage =
            sampleDirectMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-000000000002"),
                radio = radio,
                contactID = foreignContactId,
                dedup = "dm-${foreignContactId.value}-7-abcd",
            )
        val foreignReaction =
            ReactionDto(
                id = Uuid.normalized("R0000000-0000-0000-0000-000000000002"),
                messageID = foreignMessage.id,
                emoji = "👍",
                senderName = "Alice",
                messageHash = "abcd1234",
                rawText = ":+1:",
                receivedAt = 1.0,
                radioID = radio,
                contactID = foreignContactId,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    contacts = listOf(foreignContact),
                    messages = listOf(foreignMessage),
                    reactions = listOf(foreignReaction),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // The backup contact merged into the local row, so its id never lands in the store.
        assertEquals(0, result.contactsInserted)
        assertNull(db.contactDao().getById(foreignContactId))

        // Restored history must resolve to the LOCAL contact row, dedup key rewritten in lockstep.
        val message = db.messageDao().getById(foreignMessage.id)!!
        assertEquals(localContactId, message.contactID)
        assertEquals("dm-${localContactId.value}-7-abcd", message.deduplicationKey)
        val reaction = db.reactionDao().listByMessage(foreignMessage.id).single()
        assertEquals(localContactId, reaction.contactID)
        db.close()
    }

    @Test
    fun importDropsChannelAndMessagesWhenNoFreeSlot() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000AA")
        seedDevice(db, radio, maxChannels = 2)
        // Fill slots 0 and 1 so capacity 2 has no free relocatable slot.
        seedChannel(db, radio, index = 0, secretByte = 0x11)
        seedChannel(db, radio, index = 1, secretByte = 0x22)

        val foreignChannel =
            ChannelDto(
                id = Uuid.normalized("C0000000-0000-0000-0000-0000000000FF"),
                radioID = radio,
                index = 1,
                name = "#dropped",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0xAA.toByte() },
                isEnabled = true,
                unreadCount = 0,
            )
        val foreignMessage =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000FF"),
                radio = radio,
                channelIndex = 1,
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    channels = listOf(foreignChannel),
                    messages = listOf(foreignMessage),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(0, result.channelsInserted)
        assertEquals(1, result.channelsDropped)
        assertEquals(1, result.messagesDropped)
        assertEquals(0, result.messagesInserted)
        assertNull(db.channelDao().listAll().firstOrNull { it.name == "#dropped" })
        assertNull(db.messageDao().getById(foreignMessage.id))
        // Channel drop + its orphaned message both count toward the success UI total.
        assertEquals(2, result.totalDropped)
        assertTrue(result.channelSlotsAffected[radio]?.contains(1) == true)
        db.close()
    }

    @Test
    fun importDropsCollidingChannelWhenEnvelopeMaxChannelsIsZero() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000A0")
        val publicKey = ByteArray(32) { 0xA0.toByte() }
        seedDevice(db, radio, maxChannels = 8, publicKey = publicKey)
        seedChannel(db, radio, index = 3, secretByte = 0x11)

        val foreignChannel =
            ChannelDto(
                id = Uuid.normalized("C0000000-0000-0000-0000-0000000000A0"),
                radioID = radio,
                index = 3,
                name = "#zero-cap",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0xBB.toByte() },
                isEnabled = true,
                unreadCount = 0,
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey).copy(maxChannels = 0)),
                    channels = listOf(foreignChannel),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(0, result.channelsInserted)
        assertEquals(1, result.channelsDropped)
        assertNull(db.channelDao().listAll().firstOrNull { it.name == "#zero-cap" })
        db.close()
    }

    @Test
    fun importRelocatesChannelAndRemapsMessages() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000BB")
        seedDevice(db, radio, maxChannels = 8)
        seedChannel(db, radio, index = 2, secretByte = 0x11)

        val foreignChannel =
            ChannelDto(
                id = Uuid.normalized("C0000000-0000-0000-0000-0000000000EE"),
                radioID = radio,
                index = 2,
                name = "#relocated",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0xBB.toByte() },
                isEnabled = true,
                unreadCount = 3,
            )
        val foreignMessage =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000EE"),
                radio = radio,
                channelIndex = 2,
                dedup = "ch-2-99-n-abcd",
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    channels = listOf(foreignChannel),
                    messages = listOf(foreignMessage),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(1, result.channelsInserted)
        assertEquals(0, result.channelsDropped)
        assertEquals(1, result.messagesInserted)
        assertEquals(0, result.messagesDropped)
        val placed = db.channelDao().listAll().first { it.name == "#relocated" }
        // Slot 2 occupied by local secret 0x11 → lowest free relocatable is 1.
        assertEquals(1, placed.index)
        val message = db.messageDao().getById(foreignMessage.id)!!
        assertEquals(1, message.channelIndex)
        assertEquals("ch-1-99-n-abcd", message.deduplicationKey)
        db.close()
    }

    @Test
    fun importMatchesDeviceByPublicKeyAndRemapsRadioIDAcrossFamilies() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val localRadio = Uuid.normalized("A0000000-0000-0000-0000-00000000000A")
        val foreignRadio = Uuid.normalized("A0000000-0000-0000-0000-00000000000B")
        val sharedPublicKey = ByteArray(32) { 0x99.toByte() }
        seedDevice(db, localRadio, maxChannels = 8, publicKey = sharedPublicKey)

        val foreignDevice = sampleDevice(foreignRadio, publicKey = sharedPublicKey)
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val foreignContact = sampleContact(contactId, foreignRadio, publicKey = ByteArray(32) { 0x01 })
        val foreignChannel =
            ChannelDto(
                id = Uuid.normalized("C0000000-0000-0000-0000-000000000002"),
                radioID = foreignRadio,
                index = 0,
                name = "#general",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x33 },
                isEnabled = true,
                unreadCount = 0,
            )
        val foreignMessage = sampleChannelMessage(
            id = Uuid.normalized("M0000000-0000-0000-0000-000000000001"),
            radio = foreignRadio,
            channelIndex = 0,
        )
        val foreignReaction =
            ReactionDto(
                id = Uuid.normalized("R0000000-0000-0000-0000-000000000001"),
                messageID = foreignMessage.id,
                emoji = "👍",
                senderName = "Bob",
                messageHash = "abcd1234",
                rawText = ":+1:",
                receivedAt = 1.0,
                radioID = foreignRadio,
                channelIndex = 0,
            )
        val foreignSession =
            RemoteNodeSessionDto(
                id = Uuid.normalized("S0000000-0000-0000-0000-000000000001"),
                radioID = foreignRadio,
                publicKey = ByteArray(32) { 0x44 },
                name = "Repeater",
                role = RemoteNodeRole.REPEATER,
                latitude = 0.0,
                longitude = 0.0,
                isConnected = false,
                permissionLevel = RoomPermissionLevel.GUEST,
                unreadCount = 0,
                notificationLevel = NotificationLevel.ALL,
                isPinned = false,
                neighborCount = 0,
                lastSyncTimestamp = 0,
            )
        val foreignTracePath =
            SavedTracePathDto(
                id = Uuid.normalized("T0000000-0000-0000-0000-000000000001"),
                radioID = foreignRadio,
                name = "Home path",
                pathBytes = byteArrayOf(1, 2, 3),
                hashSize = 1,
                createdDate = 1.0,
            )
        val foreignBlocked =
            BlockedChannelSenderDto(
                id = Uuid.normalized("K0000000-0000-0000-0000-000000000001"),
                name = "Spammer",
                radioID = foreignRadio,
                dateBlocked = 1.0,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(foreignDevice),
                    contacts = listOf(foreignContact),
                    channels = listOf(foreignChannel),
                    messages = listOf(foreignMessage),
                    reactions = listOf(foreignReaction),
                    remoteNodeSessions = listOf(foreignSession),
                    savedTracePaths = listOf(foreignTracePath),
                    blockedChannelSenders = listOf(foreignBlocked),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // A publicKey match must never mint a fresh radioID for a device already on this install.
        assertEquals(0, result.devicesInserted)
        assertEquals(0, result.devicesMerged)
        assertEquals(1, db.deviceDao().listAll().size)

        // Every child family's radioID lands on the LOCAL radioID, not the backup's foreign one.
        assertEquals(localRadio, db.contactDao().getById(contactId)!!.radioID)
        val placedChannel = db.channelDao().listAll().first { it.name == "#general" }
        assertEquals(localRadio, placedChannel.radioID)
        assertEquals(localRadio, db.messageDao().getById(foreignMessage.id)!!.radioID)
        assertEquals(localRadio, db.reactionDao().listByMessage(foreignMessage.id).first().radioID)
        assertEquals(1, db.remoteNodeSessionDao().getAll().count { it.radioID == localRadio })
        assertEquals(1, db.savedTracePathDao().listByRadio(localRadio).size)
        assertEquals(1, db.blockedChannelSenderDao().listByRadio(localRadio).size)
        assertEquals(0, db.savedTracePathDao().listByRadio(foreignRadio).size)
        assertEquals(0, db.blockedChannelSenderDao().listByRadio(foreignRadio).size)
        db.close()
    }

    @Test
    fun importKeepsForeignRadioIDWhenNoPublicKeyMatch() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val foreignRadio = Uuid.normalized("A0000000-0000-0000-0000-00000000000C")
        val foreignDevice = sampleDevice(foreignRadio, publicKey = ByteArray(32) { 0x77 })
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000010")
        val foreignContact = sampleContact(contactId, foreignRadio, publicKey = ByteArray(32) { 0x02 })

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(foreignDevice),
                    contacts = listOf(foreignContact),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(1, result.devicesInserted)
        assertEquals(0, result.devicesMerged)
        val insertedDevice = db.deviceDao().listAll().single()
        // The backup's own radioID is kept (never re-minted) so already-remapped child rows
        // stay attached; only the volatile live-connection `id` differs from the backup's.
        assertEquals(foreignRadio, insertedDevice.radioID)
        assertTrue(insertedDevice.id != foreignDevice.id)
        assertEquals(foreignRadio, db.contactDao().getById(contactId)!!.radioID)
        db.close()
    }

    @Test
    fun aSameRadioDeviceOnlyReimportDoesNotCountAsRestored() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000AA")
        val key = ByteArray(32) { 0x42 }
        seedDevice(db, radio, maxChannels = 8, publicKey = key)

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey = key)),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(0, result.devicesInserted)
        assertEquals(0, result.devicesMerged)
        assertFalse(result.hasRestoredChanges)
        assertEquals(1, db.deviceDao().listAll().size)
        db.close()
    }

    @Test
    fun importsPreviouslyDroppedFamilies() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-00000000000D")
        seedDevice(db, radio, maxChannels = 8, publicKey = ByteArray(32) { 0x55 })

        val message = sampleChannelMessage(
            id = Uuid.normalized("M0000000-0000-0000-0000-000000000010"),
            radio = radio,
            channelIndex = 0,
        )
        val repeat =
            MessageRepeatDto(
                id = Uuid.normalized("MR000000-0000-0000-0000-000000000001"),
                messageID = message.id,
                receivedAt = 2.0,
                pathNodes = byteArrayOf(1, 2),
                pathLength = 2,
            )
        val orphanRepeat =
            MessageRepeatDto(
                id = Uuid.normalized("MR000000-0000-0000-0000-000000000002"),
                messageID = Uuid.normalized("M0000000-0000-0000-0000-FFFFFFFFFFFF"),
                receivedAt = 2.0,
                pathNodes = byteArrayOf(1),
                pathLength = 1,
            )
        val session =
            RemoteNodeSessionDto(
                id = Uuid.normalized("S0000000-0000-0000-0000-000000000010"),
                radioID = radio,
                publicKey = ByteArray(32) { 0x66 },
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
        val roomMessage =
            RoomMessageDto(
                id = Uuid.normalized("RM000000-0000-0000-0000-000000000001"),
                sessionID = session.id,
                authorKeyPrefix = byteArrayOf(1, 2, 3, 4),
                text = "hi room",
                timestamp = 5,
                createdAt = 5.0,
                isFromSelf = false,
                deduplicationKey = "dedup-1",
                statusRawValue = MessageStatus.DELIVERED.raw,
                retryAttempt = 0,
                maxRetryAttempts = 0,
            )
        val tracePath =
            SavedTracePathDto(
                id = Uuid.normalized("T0000000-0000-0000-0000-000000000010"),
                radioID = radio,
                name = "Ridge",
                pathBytes = byteArrayOf(9, 8, 7),
                hashSize = 1,
                createdDate = 1.0,
                runs = listOf(
                    TracePathRunDto(
                        id = Uuid.normalized("TR000000-0000-0000-0000-000000000001"),
                        date = 1.0,
                        success = true,
                        roundTripMs = 500,
                        hopsSNR = listOf(1.5, 2.5),
                    ),
                ),
            )
        val blocked =
            BlockedChannelSenderDto(
                id = Uuid.normalized("K0000000-0000-0000-0000-000000000010"),
                name = "Troll",
                radioID = radio,
                dateBlocked = 1.0,
            )
        val snapshot =
            NodeStatusSnapshotDto(
                id = Uuid.normalized("N0000000-0000-0000-0000-000000000001"),
                timestamp = 1.0,
                nodePublicKey = ByteArray(32) { 0x88.toByte() },
                batteryMillivolts = 4100,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(message),
                    messageRepeats = listOf(repeat, orphanRepeat),
                    remoteNodeSessions = listOf(session),
                    roomMessages = listOf(roomMessage),
                    savedTracePaths = listOf(tracePath),
                    blockedChannelSenders = listOf(blocked),
                    nodeStatusSnapshots = listOf(snapshot),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(1, result.messageRepeatsInserted)
        assertEquals(1, result.roomMessagesInserted)
        assertEquals(1, result.savedTracePathsInserted)
        assertEquals(1, result.blockedChannelSendersInserted)
        assertEquals(1, result.nodeStatusSnapshotsInserted)

        assertEquals(1, db.messageRepeatDao().listByMessage(message.id).size)
        // The orphan repeat's parent message never made it into this backup, so it must be skipped.
        assertTrue(db.messageRepeatDao().listAll().none { it.id == orphanRepeat.id })
        val insertedSession = db.remoteNodeSessionDao().getAll().first { it.name == "Room" }
        assertEquals(1, db.roomMessageDao().listBySession(insertedSession.id).size)
        val savedPath = db.savedTracePathDao().listByRadio(radio).first()
        assertEquals(1, db.tracePathRunDao().listByPath(savedPath.id).size)
        assertEquals(1, db.blockedChannelSenderDao().listByRadio(radio).size)
        assertEquals(1, db.nodeStatusSnapshotDao().listAll().size)
        db.close()
    }

    @Test
    fun importAppendsNewRunToExistingSavedTracePathOnReimport() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-00000000000E")
        val pathBytes = byteArrayOf(4, 5, 6)
        val firstRun =
            TracePathRunDto(
                id = Uuid.normalized("TR000000-0000-0000-0000-000000000010"),
                date = 1.0,
                success = true,
                roundTripMs = 400,
            )
        val firstImport =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    savedTracePaths = listOf(
                        SavedTracePathDto(
                            id = Uuid.normalized("T0000000-0000-0000-0000-000000000020"),
                            radioID = radio,
                            name = "Loop",
                            pathBytes = pathBytes,
                            hashSize = 1,
                            createdDate = 1.0,
                            runs = listOf(firstRun),
                        ),
                    ),
                ),
            )
        val firstResult = RoomBackupImporter.importEnvelope(db, firstImport)
        assertEquals(1, firstResult.savedTracePathsInserted)

        val secondRun =
            TracePathRunDto(
                id = Uuid.normalized("TR000000-0000-0000-0000-000000000011"),
                date = 2.0,
                success = false,
                roundTripMs = 900,
            )
        // Re-exporting the same path from a different device mints a new SavedTracePath id, but the
        // (radioID, pathBytes, hashSize) key must still match the local row and only append the run.
        val secondImport =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 2.0,
                    savedTracePaths = listOf(
                        SavedTracePathDto(
                            id = Uuid.normalized("T0000000-0000-0000-0000-000000000021"),
                            radioID = radio,
                            name = "Loop",
                            pathBytes = pathBytes,
                            hashSize = 1,
                            createdDate = 1.0,
                            runs = listOf(firstRun, secondRun),
                        ),
                    ),
                ),
            )
        val secondResult = RoomBackupImporter.importEnvelope(db, secondImport)
        assertEquals(0, secondResult.savedTracePathsInserted)
        assertEquals(1, secondResult.savedTracePathsMerged)
        assertEquals(1, secondResult.savedTracePathsSkipped)
        assertEquals(1, db.savedTracePathDao().listByRadio(radio).size)
        val localPath = db.savedTracePathDao().listByRadio(radio).single()
        assertEquals(2, db.tracePathRunDao().listByPath(localPath.id).size)
        db.close()
    }

    @Test
    fun importSkipsDuplicateMessageIdsWithinOneEnvelope() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000D1")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000D1")
        val sharedId = Uuid.normalized("M0000000-0000-0000-0000-0000000000D1")
        val first = sampleDirectMessage(sharedId, radio, contactId, "shared-id-first").copy(text = "First body")
        val second = sampleDirectMessage(sharedId, radio, contactId, "shared-id-second").copy(text = "Second body")
        val repeat =
            MessageRepeatDto(
                id = Uuid.normalized("MR000000-0000-0000-0000-0000000000D1"),
                messageID = sharedId,
                receivedAt = 9.0,
                pathNodes = byteArrayOf(0x31),
                pathLength = 1,
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(first, second),
                    messageRepeats = listOf(repeat),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        assertEquals(1, result.messagesInserted)
        assertEquals(1, result.messagesSkipped)
        assertEquals(1, result.messageRepeatsInserted)
        val stored = db.messageDao().listByRadio(radio)
        assertEquals(1, stored.size)
        assertEquals(sharedId, stored.single().id)
        assertEquals("First body", stored.single().text)
        assertEquals(1, db.messageRepeatDao().listByMessage(sharedId).size)
        db.close()
    }

    @Test
    fun importDoesNotDuplicateHistoryAlreadyReceivedLive() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C1")

        // Rows the store already holds from live sync, with the ids this install minted.
        val liveKeyed =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000L1"),
                radio = radio,
                channelIndex = 0,
                dedup = "ch-0-1-n-abcd",
            )
        val liveUnkeyed =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000L2"),
                radio = radio,
                channelIndex = 0,
            ).copy(timestamp = 2, deduplicationKey = null, senderNodeName = "Bob")
        val liveOutgoing =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000L3"),
                radio = radio,
                channelIndex = 0,
            ).copy(timestamp = 3, direction = MessageDirection.OUTGOING, deduplicationKey = null)
        for (dto in listOf(liveKeyed, liveUnkeyed, liveOutgoing)) db.messageDao().upsert(dto.toEntity())
        val liveReaction =
            sampleReaction(Uuid.normalized("R0000000-0000-0000-0000-0000000000L1"), liveKeyed.id, radio)
        db.reactionDao().upsert(liveReaction.toEntity())

        // The same traffic as it appears in a backup: different row ids, identical content.
        val backupKeyed = liveKeyed.copy(id = Uuid.normalized("M0000000-0000-0000-0000-0000000000B1"))
        val backupUnkeyed = liveUnkeyed.copy(id = Uuid.normalized("M0000000-0000-0000-0000-0000000000B2"))
        val backupOutgoing = liveOutgoing.copy(id = Uuid.normalized("M0000000-0000-0000-0000-0000000000B3"))
        val backupReaction =
            sampleReaction(Uuid.normalized("R0000000-0000-0000-0000-0000000000B1"), backupKeyed.id, radio)
        val backupRepeat =
            MessageRepeatDto(
                id = Uuid.normalized("MR000000-0000-0000-0000-0000000000B1"),
                messageID = backupKeyed.id,
                receivedAt = 9.0,
                pathNodes = byteArrayOf(3, 4),
                pathLength = 2,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(backupKeyed, backupUnkeyed, backupOutgoing),
                    messageRepeats = listOf(backupRepeat),
                    reactions = listOf(backupReaction),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // Only the outgoing message is a genuinely new row: it keys on its own id, never on content.
        assertEquals(1, result.messagesInserted)
        assertEquals(2, result.messagesSkipped)
        assertEquals(4, db.messageDao().listByRadio(radio).size)
        assertNull(db.messageDao().getById(backupKeyed.id))
        assertNull(db.messageDao().getById(backupUnkeyed.id))
        // The reaction and the repeat follow the deduplicated message onto the local row that won it.
        assertEquals(0, result.reactionsInserted)
        assertEquals(1, result.reactionsSkipped)
        assertEquals(1, db.reactionDao().listByMessage(liveKeyed.id).size)
        assertEquals(1, result.messageRepeatsInserted)
        assertEquals(0, result.messageRepeatsSkipped)
        assertEquals(1, db.messageRepeatDao().listByMessage(liveKeyed.id).size)
        db.close()
    }

    @Test
    fun importSkipsReactionsWhoseParentMessageIsAbsent() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C7")
        val presentParent =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000P1"),
                radio = radio,
                channelIndex = 0,
                dedup = "ch-0-1-n-beef",
            )
        // No message with this id is in the envelope or in the store: the reaction has no parent.
        val missingParent = Uuid.normalized("M0000000-0000-0000-0000-0000000000FF")
        val keptReaction =
            sampleReaction(Uuid.normalized("R0000000-0000-0000-0000-0000000000P1"), presentParent.id, radio)
        val orphanReaction =
            sampleReaction(Uuid.normalized("R0000000-0000-0000-0000-0000000000F1"), missingParent, radio)

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(presentParent),
                    reactions = listOf(keptReaction, orphanReaction),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // An orphan reaction can never render — its bubble does not exist — so it is skipped, not stored.
        assertEquals(1, result.reactionsInserted)
        assertEquals(1, result.reactionsSkipped)
        assertEquals(1, db.reactionDao().listByMessage(presentParent.id).size)
        assertEquals(0, db.reactionDao().listByMessage(missingParent).size)
        db.close()
    }

    @Test
    fun importRecomputesTheRepeatAndReactionCachesOnMessagesThatGainedChildren() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C8")
        val message =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000S1"),
                radio = radio,
                channelIndex = 0,
                dedup = "ch-0-1-n-cafe",
            )
        val reaction = sampleReaction(Uuid.normalized("R0000000-0000-0000-0000-0000000000S1"), message.id, radio)
        val repeat =
            MessageRepeatDto(
                id = Uuid.normalized("MR000000-0000-0000-0000-0000000000S1"),
                messageID = message.id,
                receivedAt = 9.0,
                pathNodes = byteArrayOf(3, 4),
                pathLength = 2,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(message),
                    messageRepeats = listOf(repeat),
                    reactions = listOf(reaction),
                ),
            )

        RoomBackupImporter.importEnvelope(db, envelope)

        // The bubble renders from these caches, not from the child rows, so a restore that leaves them
        // at the exported values hides the reaction and under-reports the repeats.
        val restored = db.messageDao().getById(message.id)!!
        assertEquals("👍:1", restored.reactionSummary)
        assertEquals(1, restored.heardRepeats)
        db.close()
    }

    @Test
    fun importedMessageDropsCachedLinkPreviewBlobs() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000CA")
        val message =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000CA"),
                radio = radio,
                channelIndex = 0,
            ).copy(
                linkPreviewFetched = true,
                linkPreviewURL = "https://example.com/page",
                linkPreviewTitle = "Page",
                linkPreviewImageData = byteArrayOf(1, 2, 3),
                linkPreviewIconData = byteArrayOf(4, 5),
            )
        // Bypass export redaction so this is an older envelope that still carried blobs.
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    messages = listOf(message),
                ),
            ).copy(messages = listOf(message))

        RoomBackupImporter.importEnvelope(db, envelope)

        val restored = db.messageDao().getById(message.id)!!
        assertNull(restored.linkPreviewImageData)
        assertNull(restored.linkPreviewIconData)
        assertFalse(restored.linkPreviewFetched)
        assertEquals("https://example.com/page", restored.linkPreviewURL)
        assertEquals("Page", restored.linkPreviewTitle)
        db.close()
    }

    @Test
    fun importedRemoteNodeSessionIsNeverMarkedConnected() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C9")
        val session =
            RemoteNodeSessionDto(
                id = Uuid.normalized("50000000-0000-0000-0000-0000000000C9"),
                radioID = radio,
                publicKey = ByteArray(32) { 0x44 },
                name = "Ridge repeater",
                role = RemoteNodeRole.REPEATER,
                latitude = 0.0,
                longitude = 0.0,
                isConnected = true,
                permissionLevel = RoomPermissionLevel.GUEST,
                unreadCount = 0,
                notificationLevel = NotificationLevel.ALL,
                isPinned = false,
                neighborCount = 0,
                lastSyncTimestamp = 0,
            )

        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    remoteNodeSessions = listOf(session),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)

        // A restored login is not a live one; the flag would otherwise show the node as logged in.
        assertEquals(1, result.sessionsInserted)
        assertEquals(false, db.remoteNodeSessionDao().getAll().single().isConnected)
        db.close()
    }

    @Test
    fun importRollsBackEveryFamilyWhenItFailsPartWayThrough() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C2")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000C2")
        val message =
            sampleChannelMessage(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000C2"),
                radio = radio,
                channelIndex = 0,
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey = ByteArray(32) { 0x21 })),
                    contacts = listOf(sampleContact(contactId, radio, ByteArray(32) { 0x22 })),
                    messages = listOf(message),
                ),
            )

        assertFailsWith<IllegalStateException> {
            RoomBackupImporter.importEnvelope(db, envelope, beforeCommit = { error("import failed") })
        }

        // A half-merged store is worse than no restore at all: nothing may survive the failure.
        assertEquals(0, db.deviceDao().listAll().size)
        assertNull(db.contactDao().getById(contactId))
        assertNull(db.messageDao().getById(message.id))
        db.close()
    }

    @Test
    fun importRollsBackWhenTheCallerCancels() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C4")
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey = ByteArray(32) { 0x31 })),
                ),
            )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            RoomBackupImporter.importEnvelope(db, envelope, beforeCommit = {
                throw kotlinx.coroutines.CancellationException("user cancelled")
            })
        }

        assertEquals(0, db.deviceDao().listAll().size)
        db.close()
    }

    @Test
    fun importRefusesWhenTheRadioIsConnectedAndWritesNothing() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C5")
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey = ByteArray(32) { 0x41 })),
                ),
            )

        assertFailsWith<AppBackupError.RadioConnected> {
            RoomBackupImporter.importEnvelope(db, envelope, radioConnected = { true })
        }
        assertEquals(0, db.deviceDao().listAll().size)
        db.close()
    }

    @Test
    fun importRefusesWhenTheRadioConnectsAfterTheWriterIsHeld() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C6")
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    devices = listOf(sampleDevice(radio, publicKey = ByteArray(32) { 0x42 })),
                ),
            )
        var heldWriter = false
        val radioConnected = {
            if (!heldWriter) {
                heldWriter = true
                false
            } else {
                true
            }
        }

        assertFailsWith<AppBackupError.RadioConnected> {
            RoomBackupImporter.importEnvelope(db, envelope, radioConnected = radioConnected)
        }
        assertEquals(0, db.deviceDao().listAll().size)
        db.close()
    }

    @Test
    fun affectedChannelSlotsClearTheirDrafts() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000C3")
        seedDevice(db, radio, maxChannels = 8)
        seedChannel(db, radio, index = 2, secretByte = 0x11)

        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val relocatedSlot = ChatConversationID.channel(radio, 1)
        val untouchedSlot = ChatConversationID.channel(radio, 5)
        drafts.setDraft("half-typed message", relocatedSlot)
        drafts.setDraft("keep me", untouchedSlot)

        val foreignChannel =
            ChannelDto(
                id = Uuid.normalized("C0000000-0000-0000-0000-0000000000C3"),
                radioID = radio,
                index = 2,
                name = "#relocated",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0xBB.toByte() },
                isEnabled = true,
                unreadCount = 0,
            )
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    channels = listOf(foreignChannel),
                ),
            )

        val result = RoomBackupImporter.importEnvelope(db, envelope)
        result.clearAffectedChannelDrafts(ChatDraftEditors(drafts, this))

        // Slot 1 now holds a different channel, so the draft typed against the old one must not resurface.
        assertNull(drafts.draft(relocatedSlot))
        assertEquals("keep me", drafts.draft(untouchedSlot))
        db.close()
    }

    private fun sampleReaction(id: Uuid, messageID: Uuid, radio: Uuid): ReactionDto = ReactionDto(
        id = id,
        messageID = messageID,
        emoji = "👍",
        senderName = "Bob",
        messageHash = "abcd1234",
        rawText = ":+1:",
        receivedAt = 1.0,
        radioID = radio,
        channelIndex = 0,
    )

    private fun sampleDevice(radio: Uuid, publicKey: ByteArray): DeviceDto = DeviceDto(
        id = Uuid.normalized("D0000000-0000-0000-0000-${radio.value.takeLast(12)}"),
        radioID = radio,
        publicKey = publicKey,
        nodeName = "Node",
        firmwareVersion = 1,
        firmwareVersionString = "v1",
        manufacturerName = "m",
        buildDate = "d",
        maxContacts = 10,
        maxChannels = 8,
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

    private fun sampleContact(id: Uuid, radio: Uuid, publicKey: ByteArray): ContactDto = ContactDto(
        id = id,
        radioID = radio,
        publicKey = publicKey,
        name = "Alice",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 255,
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

    private suspend fun seedDevice(
        db: MeshDatabase,
        radio: Uuid,
        maxChannels: Int,
        publicKey: ByteArray = ByteArray(32) { 0x11 },
    ) {
        db.deviceDao().upsert(
            DeviceEntity(
                id = Uuid.normalized("D0000000-0000-0000-0000-${radio.value.takeLast(12)}"),
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
            ),
        )
    }

    private suspend fun seedChannel(
        db: one.meshcore.shared.persistence.MeshDatabase,
        radio: Uuid,
        index: Int,
        secretByte: Int,
    ) {
        db.channelDao().upsert(
            ChannelEntity(
                id = Uuid.normalized("C0000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"),
                radioID = radio,
                index = index,
                name = "local$index",
                secret = ByteArray(ChannelSecret.SIZE_BYTES) { secretByte.toByte() },
                isEnabled = true,
                unreadCount = 0,
            ),
        )
    }

    private fun sampleDirectMessage(id: Uuid, radio: Uuid, contactID: Uuid, dedup: String): MessageDto = MessageDto(
        id = id,
        radioID = radio,
        text = "hello",
        timestamp = 7,
        createdAt = 7.0,
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
        contactID = contactID,
        deduplicationKey = dedup,
    )

    private fun sampleChannelMessage(
        id: Uuid,
        radio: Uuid,
        channelIndex: Int,
        dedup: String = "ch-$channelIndex-1-n-abcd",
    ): MessageDto = MessageDto(
        id = id,
        radioID = radio,
        text = "hello",
        timestamp = 1,
        createdAt = 1.0,
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
        channelIndex = channelIndex,
        deduplicationKey = dedup,
    )
}
