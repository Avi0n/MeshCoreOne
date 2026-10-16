package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.simulator.MockDataProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

@Suppress("MagicNumber")
internal object QueueProcessReplayRows {
    const val SEED_TIME = 1_700_000_000.0
    const val REPLAY_TIME = SEED_TIME + 3600
    const val CHANNEL = 3
    val radioA = id(1)
    val radioB = id(2)
    val selfKey = ByteArray(32) { 1 }
    val texts = listOf("queue DM first Δ", "queue DM second", "queue channel first Δ", "queue channel second")
    private val deliveredAckCodes = listOf(673_059_841L, 673_059_842L)
    val messages = texts.indices.map { message(it) }
    val controlMessage = message(4).copy(radioID = radioB, contactID = id(31), channelIndex = null)
    val controlPending = PendingSendDto(
        id = id(104),
        radioID = radioB,
        messageID = controlMessage.id,
        kind = PendingSendKind.DM,
        sequence = 23,
        enqueuedAtEpochSeconds = SEED_TIME - 9,
        contactID = id(31),
        attemptCount = 7,
    )

    fun id(number: Int): Uuid = Uuid.normalized("C0000000-0000-0000-0000-" + number.toString().padStart(12, '0'))

    fun contact(other: Boolean = false): ContactDto = ContactDto(
        id = id(if (other) 31 else 30), radioID = if (other) radioB else radioA,
        publicKey = ByteArray(32) { if (other) 9 else 7 }, name = if (other) "Other control" else "Queue peer",
        typeRawValue = ContactType.CHAT.raw, flags = 0, outPathLength = 0, outPath = byteArrayOf(),
        lastAdvertTimestamp = 0, latitude = 0.0, longitude = 0.0, lastModified = 0,
        isBlocked = false, isMuted = false, isFavorite = true, unreadCount = 0,
    )

    private fun device(other: Boolean) = MockDataProvider.simulatorDevice(SEED_TIME).copy(
        id = if (other) radioB else radioA,
        radioID = if (other) radioB else radioA,
        publicKey = ByteArray(32) { if (other) 3 else 2 },
        nodeName = if (other) "Queue B" else "Queue A",
    )

    private fun channel(other: Boolean) = ChannelEntity(
        id = id(if (other) 41 else 40),
        radioID = if (other) radioB else radioA,
        index = CHANNEL,
        name = "Synthetic queue",
        secret = ByteArray(16) { if (other) 4 else 5 },
        isEnabled = true,
        unreadCount = 0,
    )

    private fun message(index: Int) = MessageDto(
        id = id(10 + index), radioID = radioA, text = texts.getOrElse(index) { "Other queued control" },
        timestamp = SEED_TIME.toLong(), createdAt = SEED_TIME, direction = MessageDirection.OUTGOING,
        status = MessageStatus.PENDING, textType = TextType.PLAIN, pathLength = 0, isRead = false,
        heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        linkPreviewFetched = false, containsSelfMention = false, mentionSeen = false, timestampCorrected = false,
        contactID = if (index < 2) id(30) else null, channelIndex = if (index < 2) null else CHANNEL,
    )

    fun pending(index: Int, attempted: Boolean): PendingSendDto = PendingSendDto(
        id = id(100 + index), radioID = radioA, messageID = messages[index].id,
        kind = if (index < 2) PendingSendKind.DM else PendingSendKind.CHANNEL,
        sequence = index + 1, enqueuedAtEpochSeconds = SEED_TIME,
        contactID = if (index < 2) id(30) else null, channelIndex = if (index < 2) null else CHANNEL,
        messageText = if (index < 2) "" else texts[index],
        messageTimestamp = if (index < 2) 0 else SEED_TIME.toLong(),
        localNodeName = if (index < 2) null else "Queue A", attemptCount = if (attempted) 1 else 0,
    )

    fun expectedMessage(index: Int, drained: Boolean): MessageDto {
        val original = messages[index]
        if (!drained) return original
        val timestamp = if (index == 1) REPLAY_TIME.toLong() else SEED_TIME.toLong()
        return original.copy(
            timestamp = timestamp,
            status = if (index < 2) MessageStatus.DELIVERED else MessageStatus.SENT,
            ackCode = if (index < 2) deliveredAckCodes[index] else null,
        )
    }

    fun ack(index: Int): ByteArray = byteArrayOf((index + 1).toByte(), 20, 30, 40)

    suspend fun seedControls(db: MeshDatabase) {
        for (other in listOf(false, true)) {
            db.deviceDao().upsert(device(other))
            db.contactDao().upsert(contact(other).toEntity())
            db.channelDao().upsert(channel(other))
        }
        db.messageDao().upsert(controlMessage.toEntity())
        RoomPendingSendStore(db.pendingSendDao()).upsertPendingSend(controlPending)
    }

    suspend fun verify(db: MeshDatabase, drained: Boolean) {
        val store = RoomPendingSendStore(db.pendingSendDao())
        val expectedPending = if (drained) {
            emptyList()
        } else {
            texts.indices.map {
                pending(
                    it,
                    attempted =
                    it == 0 || it == 2,
                )
            }
        }
        assertEquals(expectedPending, store.fetchPendingSends(radioA))
        assertEquals(expectedPending.map { it.toEntity() }, db.pendingSendDao().listByRadio(radioA))
        assertEquals(listOf(controlPending.toEntity()), db.pendingSendDao().listByRadio(radioB))
        assertEquals(listOf(controlPending), store.fetchPendingSends(radioB))
        for (other in listOf(false, true)) {
            val radio = if (other) radioB else radioA
            val actualDevice = db.deviceDao().listByRadio(radio).single()
            assertArrayEquals(device(other).publicKey, actualDevice.publicKey)
            assertEquals(device(other).copy(publicKey = actualDevice.publicKey), actualDevice)
            val actualContact = db.contactDao().listByRadio(radio).single()
            val expectedContact = contact(other).copy(
                lastMessageDate = when {
                    other -> null
                    drained -> REPLAY_TIME
                    else -> SEED_TIME
                },
            ).toEntity()
            assertArrayEquals(expectedContact.publicKey, actualContact.publicKey)
            assertArrayEquals(expectedContact.outPath, actualContact.outPath)
            assertEquals(
                expectedContact.copy(publicKey = actualContact.publicKey, outPath = actualContact.outPath),
                actualContact,
            )
            val actualChannel = db.channelDao().listByRadio(radio).single()
            val expectedChannel = channel(other).copy(lastMessageDate = if (drained && !other) REPLAY_TIME else null)
            assertArrayEquals(expectedChannel.secret, actualChannel.secret)
            assertEquals(expectedChannel.copy(secret = actualChannel.secret), actualChannel)
            val expected = if (other) listOf(controlMessage) else texts.indices.map { expectedMessage(it, drained) }
            assertEquals(
                expected.map {
                    it.toEntity()
                }.sortedBy { it.id.value },
                db.messageDao().listByRadio(radio).sortedBy { it.id.value },
            )
        }
        assertEquals(emptyList<PendingSendDto>(), store.fetchPendingSends(id(3)))
    }
}
