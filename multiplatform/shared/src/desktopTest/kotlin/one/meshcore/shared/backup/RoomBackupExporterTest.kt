package one.meshcore.shared.backup

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.BlockedChannelSenderEntity
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.ReactionEntity
import one.meshcore.shared.persistence.SavedTracePathEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomBackupExporterTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("backup-export-", ".db")
        dbFile.deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun exportIncludesDeviceAndValidManifest() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        db.deviceDao().upsert(
            DeviceEntity(
                id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
                radioID = radio,
                publicKey = ByteArray(32) { 0x11 },
                nodeName = "ExportNode",
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
                lastConnected = 1_700_000_000.0,
                lastContactSync = 0,
                isActive = true,
                connectionMethods =
                listOf(
                    ConnectionMethod.WiFi("10.0.0.1", 5000, "Camp"),
                    ConnectionMethod.Bluetooth(Uuid.normalized("B0000000-0000-0000-0000-000000000001"), "x"),
                ),
            ),
        )

        val json =
            RoomBackupExporter.exportJson(
                database = db,
                appVersion = "1.2.3",
                appBuild = "99",
                exportDate = 1_700_000_000.0,
            )
        val envelope = decodeBackupEnvelope(json)
        assertTrue(envelope.manifest.validate(envelope))
        assertEquals(1, envelope.devices.size)
        assertEquals("ExportNode", envelope.devices.first().nodeName)
        assertEquals(1, envelope.devices.first().connectionMethods.size)
        assertTrue(envelope.devices.first().connectionMethods.first() is ConnectionMethod.WiFi)
        assertEquals("1.2.3", envelope.appVersion)
        db.close()
    }

    @Test
    fun exportCoversRadioPartitionsWithNoDeviceRow() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        // A partition whose Device row is gone (deleted, never re-paired) still holds real history.
        val orphanedRadio = Uuid.normalized("A0000000-0000-0000-0000-0000000000FF")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000FF")
        seedContact(db, orphanedRadio, contactId)
        db.messageDao().upsert(orphanMessage(orphanedRadio, contactId).toEntity())

        val envelope =
            decodeBackupEnvelope(
                RoomBackupExporter.exportJson(
                    database = db,
                    appVersion = "1.2.3",
                    appBuild = "99",
                    exportDate = 1_700_000_000.0,
                ),
            )

        assertEquals(0, envelope.devices.size)
        assertEquals(1, envelope.contacts.size)
        assertEquals(1, envelope.messages.size)
        assertTrue(envelope.manifest.validate(envelope))
        db.close()
    }

    @Test
    fun exportCoversPartitionsNamedOnlyByLeafFamilies() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        // The hardest partition: no device, contact, channel, session or discovered node names it, so
        // any radioID-derived scope misses it entirely. iOS `fetchAll*` still exports every row.
        val lostRadio = Uuid.normalized("A0000000-0000-0000-0000-0000000000AA")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000AA")
        val message = orphanMessage(lostRadio, contactId)
        db.messageDao().upsert(message.toEntity())
        db.reactionDao().upsert(
            ReactionEntity(
                id = Uuid.normalized("R0000000-0000-0000-0000-0000000000AA"),
                messageID = message.id,
                emoji = "👍",
                senderName = "Stranded",
                messageHash = "AABBCCDD",
                rawText = "👍",
                receivedAt = 12.0,
                radioID = lostRadio,
                contactID = contactId,
            ),
        )
        db.savedTracePathDao().upsert(
            SavedTracePathEntity(
                id = Uuid.normalized("50000000-0000-0000-0000-0000000000AA"),
                radioID = lostRadio,
                name = "Ridge relay",
                pathBytes = byteArrayOf(0x0A, 0x0B),
                hashSize = 1,
                createdDate = 13.0,
            ),
        )
        db.blockedChannelSenderDao().upsert(
            BlockedChannelSenderEntity(
                id = Uuid.normalized("B1000000-0000-0000-0000-0000000000AA"),
                name = "Spammer",
                radioID = lostRadio,
                dateBlocked = 14.0,
            ),
        )

        val envelope =
            decodeBackupEnvelope(
                RoomBackupExporter.exportJson(
                    database = db,
                    appVersion = "1.2.3",
                    appBuild = "99",
                    exportDate = 1_700_000_000.0,
                ),
            )

        assertEquals(1, envelope.messages.size)
        assertEquals(1, envelope.reactions.size)
        assertEquals(1, envelope.savedTracePaths.size)
        assertEquals(1, envelope.blockedChannelSenders.size)
        assertTrue(envelope.manifest.validate(envelope))
        db.close()
    }

    @Test
    fun exportBackfillsTheContentDedupKeyOnIncomingMessages() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000EE")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000EE")
        seedContact(db, radio, contactId)
        val incoming = orphanMessage(radio, contactId)
        val outgoing =
            incoming.copy(
                id = Uuid.normalized("M0000000-0000-0000-0000-0000000000E2"),
                direction = MessageDirection.OUTGOING,
            )
        db.messageDao().upsert(incoming.toEntity())
        db.messageDao().upsert(outgoing.toEntity())

        val envelope =
            decodeBackupEnvelope(
                RoomBackupExporter.exportJson(
                    database = db,
                    appVersion = "1.2.3",
                    appBuild = "99",
                    exportDate = 1_700_000_000.0,
                ),
            )

        // Without the key an importer can only match on id, and no two installs share message ids.
        val exportedIncoming = envelope.messages.first { it.id == incoming.id }
        assertEquals(
            DeduplicationKey.contentBased(
                contactID = contactId,
                channelIndex = null,
                senderNodeName = null,
                timestamp = incoming.timestamp,
                content = incoming.text,
            ),
            exportedIncoming.deduplicationKey,
        )
        // Outgoing messages restore on identity, so they are deliberately left without one.
        assertNull(envelope.messages.first { it.id == outgoing.id }.deduplicationKey)
        db.close()
    }

    /**
     * The blobs are image bytes fetched from an arbitrary host named in a mesh message. They must not
     * travel inside a file the user shares, and a restored row must re-resolve its preview rather than
     * trust a stale image, so `linkPreviewFetched` has to come back false.
     */
    @Test
    fun exportCarriesNoLinkPreviewBlobAndNoFetchedFlag() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000B1")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000B1")
        seedContact(db, radio, contactId)
        db.messageDao().upsert(previewedMessage(radio, contactId).toEntity())

        val json =
            RoomBackupExporter.exportJson(
                database = db,
                appVersion = "1.2.3",
                appBuild = "99",
                exportDate = 1_700_000_000.0,
            )
        val exported = decodeBackupEnvelope(json).messages.single()

        assertNull(exported.linkPreviewImageData, "exported linkPreviewImageData must be null")
        assertNull(exported.linkPreviewIconData, "exported linkPreviewIconData must be null")
        assertFalse(exported.linkPreviewFetched, "exported linkPreviewFetched must be false")
        // The keys must be absent from the wire, not merely null after decode.
        assertFalse(json.contains("linkPreviewImageData"), "image blob key must not reach the envelope")
        assertFalse(json.contains("linkPreviewIconData"), "icon blob key must not reach the envelope")
        // The URL and title are the message's own text and are needed to re-fetch, so they stay.
        assertEquals(PREVIEW_URL, exported.linkPreviewURL)
        assertEquals(PREVIEW_TITLE, exported.linkPreviewTitle)
        db.close()
    }

    /** The redaction is export-only: the app's own reads still serve the cached preview. */
    @Test
    fun ordinaryReadsStillCarryTheLinkPreviewBlobs() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000B2")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000B2")
        seedContact(db, radio, contactId)
        db.messageDao().upsert(previewedMessage(radio, contactId).toEntity())

        val read = db.messageDao().listAll().single().toDto()

        assertContentEquals(PREVIEW_IMAGE, read.linkPreviewImageData)
        assertContentEquals(PREVIEW_ICON, read.linkPreviewIconData)
        assertTrue(read.linkPreviewFetched)
        db.close()
    }

    private fun previewedMessage(radio: Uuid, contactId: Uuid): MessageDto = orphanMessage(radio, contactId)
        .copy(
            linkPreviewFetched = true,
            linkPreviewURL = PREVIEW_URL,
            linkPreviewTitle = PREVIEW_TITLE,
            linkPreviewImageData = PREVIEW_IMAGE,
            linkPreviewIconData = PREVIEW_ICON,
        )

    private suspend fun seedContact(db: MeshDatabase, radio: Uuid, contactId: Uuid) {
        db.contactDao().upsert(
            ContactEntity(
                id = contactId,
                radioID = radio,
                publicKey = ByteArray(32) { 0x33 },
                name = "Stranded",
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
                unreadMentionCount = 0,
                nickname = null,
                lastMessageDate = null,
            ),
        )
    }

    private fun orphanMessage(radio: Uuid, contactId: Uuid): MessageDto = MessageDto(
        id = Uuid.normalized("M0000000-0000-0000-0000-0000000000E1"),
        radioID = radio,
        text = "still here",
        timestamp = 11,
        createdAt = 11.0,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
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
        contactID = contactId,
    )

    private companion object {
        const val PREVIEW_URL = "https://example.org/incident"
        const val PREVIEW_TITLE = "Incident report"
        val PREVIEW_IMAGE = ByteArray(64) { 0x5A }
        val PREVIEW_ICON = ByteArray(16) { 0x3C }
    }
}
