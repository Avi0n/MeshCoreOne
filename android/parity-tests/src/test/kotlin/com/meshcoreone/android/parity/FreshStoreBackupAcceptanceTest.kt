// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Public repository routes, distinct-store restores and original 32-byte channel families.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.container.openMemoryDatabase
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.backup.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.timeline.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FreshStoreBackupAcceptanceTest : RoomProcessTest() {
    @Test fun fullExportRestoresDistinctEmptyRoomAndOfflineConsumer() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val device = DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), nodeName = "TestDevice")
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }),
                name = "Alice", typeRawValue = 4u, lastHeardTimestamp = 1_700_000_600u,
                avatarImageData = Bytes(ByteArray(8) { 0x22 }))
            val channel = ChannelDTO(radioId = radio, index = 0u, name = "General", secret = Bytes(ByteArray(16)))
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Hello", timestamp = 1_700_000_000u,
                createdAt = Instant.ofEpochSecond(1_700_000_000), status = MessageStatus.SENT,
                deduplicationKey = "integration-dedup")
            val reaction = ReactionDTO(messageID = message.id, emoji = "\uD83D\uDC4D", senderName = "Eve",
                messageHash = "hash", rawText = "raw", radioId = radio)
            store.saveDevice(device)
            store.addDeviceKnownRegion(radio, "US915")
            store.addDeviceKnownRegion(radio, "EU868")
            store.saveContact(contact)
            store.saveChannel(channel)
            store.saveMessage(message)
            store.saveReaction(reaction)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val parsed = AppBackupCodec().parseBackup(service.export(store).data)
            assertTrue(parsed.manifest.validate(parsed))
            assertEquals(4.toUByte(), parsed.contacts.single().typeRawValue)
            assertEquals(listOf("US915", "EU868"), parsed.devices.single().knownRegions)
            withFreshDestination(backgroundScope) { destination ->
                val result = service.importBackup(parsed, destination)
                for (kind in listOf(BackupModelKind.DEVICES, BackupModelKind.CONTACTS, BackupModelKind.CHANNELS,
                    BackupModelKind.MESSAGES, BackupModelKind.REACTIONS)) {
                    assertEquals(1L, result.counts.getValue(kind).inserted)
                }
                assertEquals(0L, result.totalSkipped)
                val restoredDevice = requireNotNull(destination.fetchDevice(radio))
                assertNotEquals(device.id, restoredDevice.id)
                assertEquals(listOf("US915", "EU868"), restoredDevice.knownRegions)
                val restoredContact = destination.fetchContacts(radio).single()
                assertEquals("Alice", restoredContact.name)
                assertEquals(4.toUByte(), restoredContact.typeRawValue)
                assertEquals(ContactType.CHAT, restoredContact.type)
                assertEquals(1_700_000_600u, restoredContact.lastHeardTimestamp)
                assertEquals(Bytes(ByteArray(8) { 0x22 }), restoredContact.avatarImageData)
                assertEquals("General", destination.fetchChannels(radio).single().name)
                assertEquals("Hello", requireNotNull(destination.fetchMessage(EntityKey(radio, message.id))).text)
                assertEquals(message.id, destination.fetchReactions(EntityKey(radio, message.id), 10).single().messageID)
                assertEquals(0L, service.importBackup(parsed, destination).totalInserted)
                val harness = ContainerHarness(this, destination)
                try {
                    val conversation = TimelineConversation.Direct(restoredContact)
                    val timeline = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), backgroundScope)
                    timeline.start()
                    timeline.open()
                    harness.eventually("fresh-store restored message visible") { timeline.state.value.messages.size == 1 }
                    assertEquals(message.id, timeline.state.value.messages.single().id)
                    assertEquals("Hello", timeline.state.value.messages.single().text)
                    assertTrue(harness.lifecycle.created.isEmpty())
                    assertTrue(harness.links.links.isEmpty())
                } finally {
                    harness.container.close()
                }
            }
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(listOf("US915", "EU868"), requireNotNull(store.fetchDevice(radio)).knownRegions)
        } finally {
            storage.close()
        }
    }

    @Test fun channelRepeatExportRestoresFreshParentAndCascadeWithoutDeletingSource() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            store.saveDevice(DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xBB.toByte() }), nodeName = "TestDevice"))
            store.saveChannel(ChannelDTO(radioId = radio, index = 0u, name = "General", secret = Bytes(ByteArray(16))))
            val message = MessageDTO(radioId = radio, channelIndex = 0u, text = "Hello mesh", timestamp = 1_700_000_000u,
                createdAt = Instant.ofEpochSecond(1_700_000_000), status = MessageStatus.SENT, deduplicationKey = "fresh-cascade")
            val repeat = MessageRepeatDTO(messageID = message.id, receivedAt = message.createdAt,
                pathNodes = Bytes.of(0x42), pathLength = 1u)
            store.saveMessage(message)
            store.saveMessageRepeat(radio, repeat)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val parsed = AppBackupCodec().parseBackup(service.export(store).data)
            assertEquals(1L, parsed.manifest.messageCount)
            assertEquals(1L, parsed.manifest.messageRepeatCount)
            withFreshDestination(backgroundScope) { destination ->
                val result = service.importBackup(parsed, destination)
                assertEquals(1L, result.counts.getValue(BackupModelKind.MESSAGES).inserted)
                assertEquals(1L, result.counts.getValue(BackupModelKind.MESSAGE_REPEATS).inserted)
                val key = EntityKey(radio, message.id)
                assertEquals(0.toUByte(), requireNotNull(destination.fetchMessage(key)).channelIndex)
                val restored = destination.fetchMessageRepeats(key).single()
                assertEquals(message.id, restored.messageID)
                assertEquals(Bytes.of(0x42), restored.pathNodes)
                destination.deleteMessage(key)
                assertTrue(destination.fetchMessageRepeats(key).isEmpty())
            }
            assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(repeat.id, store.fetchMessageRepeats(EntityKey(radio, message.id)).single().id)
        } finally {
            storage.close()
        }
    }

    @Test fun original32ByteSecretAndFloodScopesSurviveDistinctStoreExportImport() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            store.saveDevice(DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), nodeName = "TestDevice"))
            val publicChannel = ChannelDTO(radioId = radio, index = 0u, name = "General", secret = Bytes(ByteArray(16)))
            val regional = ChannelDTO(radioId = radio, index = 1u, name = "Regional",
                secret = Bytes(ByteArray(32) { 0x55 })).withFloodScope(ChannelFloodScope.Region("US"))
            store.saveChannel(publicChannel)
            store.saveChannel(regional)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val parsed = AppBackupCodec().parseBackup(service.export(store).data)
            assertEquals(32, parsed.channels.single { it.index == 1.toUByte() }.secret.size)
            withFreshDestination(backgroundScope) { destination ->
                assertEquals(2L, service.importBackup(parsed, destination).counts.getValue(BackupModelKind.CHANNELS).inserted)
                val channels = destination.fetchChannels(radio)
                assertEquals(2, channels.size)
                assertEquals(ChannelFloodScope.Inherit, channels.single { it.index == 0.toUByte() }.floodScope)
                val restored = channels.single { it.index == 1.toUByte() }
                assertEquals(ChannelFloodScope.Region("US"), restored.floodScope)
                assertEquals(Bytes(ByteArray(32) { 0x55 }), restored.secret)
                assertEquals("Regional", restored.name)
            }
            assertEquals(regional.secret, db.channels().byId(radio.value, regional.id)?.secret)
        } finally {
            storage.close()
        }
    }

    @OriginalCase("BackupIntegrationTests::Re-importing a stale backup never upserts a reconfigured live channel()")
    @Test fun original32ByteStaleSecretCannotOverwriteReconfiguredLiveChannel() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val live = ChannelDTO(radioId = radio, index = 3u, name = "Live Net",
                secret = Bytes(ByteArray(32) { 0xB2.toByte() }))
            store.saveChannel(live)
            val stale = live.copy(name = "Stale Net", secret = Bytes(ByteArray(32) { 0xA1.toByte() }))
            val envelope = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_000),
                appVersion = "1", appBuild = "1",
                devices = SnapshotList.of(DeviceDTO(radioId = radio,
                    publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), nodeName = "TestDevice", maxChannels = 8u)),
                channels = SnapshotList.of(stale)).withActualManifest()
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val result = service.importBackup(envelope, store)
            assertEquals(1L, result.counts.getValue(BackupModelKind.CHANNELS).inserted)
            assertEquals(0L, result.counts.getValue(BackupModelKind.CHANNELS).skipped)
            val channels = store.fetchChannels(radio)
            assertEquals(2, channels.size)
            assertEquals(live, channels.single { it.secret == Bytes(ByteArray(32) { 0xB2.toByte() }) })
            val restored = channels.single { it.secret == Bytes(ByteArray(32) { 0xA1.toByte() }) }
            assertEquals("Stale Net", restored.name)
            assertNotEquals(live.id, restored.id)
            assertNotEquals(3.toUByte(), restored.index)
            assertEquals(32, restored.secret.size)
        } finally {
            storage.close()
        }
    }

    @OriginalCase("BackupIntegrationTests::batchInsertChannels reports newly-occupied local slots for draft clearing, excluding merges()")
    @Test fun original32ByteDraftSlotsReportOnlyFreshAndRelocatedOccupancy() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val keep = ChannelDTO(radioId = radio, index = 2u, name = "Keep",
                secret = Bytes(ByteArray(32) { 0xC1.toByte() }))
            val occupied = ChannelDTO(radioId = radio, index = 4u, name = "Occupied",
                secret = Bytes(ByteArray(32) { 0xC4.toByte() }))
            store.saveChannel(keep)
            store.saveChannel(occupied)
            val merge = keep.copy(id = UUID.randomUUID(), index = 6u)
            val fresh = ChannelDTO(radioId = radio, index = 5u, name = "Fresh",
                secret = Bytes(ByteArray(32) { 0xD5.toByte() }))
            val collide = ChannelDTO(radioId = radio, index = 4u, name = "Collide",
                secret = Bytes(ByteArray(32) { 0xD4.toByte() }))
            val envelope = AppBackupEnvelope(exportDate = Instant.ofEpochSecond(1_700_000_000),
                appVersion = "1", appBuild = "1",
                devices = SnapshotList.of(DeviceDTO(radioId = radio,
                    publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), nodeName = "TestDevice", maxChannels = 8u)),
                channels = SnapshotList.of(merge, fresh, collide)).withActualManifest()
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val result = service.importBackup(envelope, store)
            assertEquals(2L, result.counts.getValue(BackupModelKind.CHANNELS).inserted)
            assertEquals(1L, result.counts.getValue(BackupModelKind.CHANNELS).skipped)
            val channels = store.fetchChannels(radio)
            assertEquals(4, channels.size)
            assertEquals(keep, channels.single { it.secret == Bytes(ByteArray(32) { 0xC1.toByte() }) })
            assertEquals(occupied, channels.single { it.secret == Bytes(ByteArray(32) { 0xC4.toByte() }) })
            val restoredFresh = channels.single { it.secret == Bytes(ByteArray(32) { 0xD5.toByte() }) }
            val relocated = channels.single { it.secret == Bytes(ByteArray(32) { 0xD4.toByte() }) }
            assertEquals(5.toUByte(), restoredFresh.index)
            assertEquals("Fresh", restoredFresh.name)
            assertEquals("Collide", relocated.name)
            assertNotEquals(4.toUByte(), relocated.index)
            val affected = result.channelSlotsAffectedByImport.getValue(radio).toSet()
            assertEquals(setOf(5.toUByte(), relocated.index), affected)
            for (slot in listOf(2u, 6u, 4u)) assertFalse(affected.contains(slot.toUByte()))
            assertTrue(channels.all { it.secret.size == 32 })
        } finally {
            storage.close()
        }
    }

    @OriginalCase("BackupIntegrationTests::Export preserves orphaned radio-scoped data after device-only delete()")
    @Test fun deviceOnlyDeleteExportsAllSevenOrphanKindsIntoDistinctEmptyStore() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val at = Instant.ofEpochSecond(1_700_000_000)
            val device = DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }),
                nodeName = "TestDevice")
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xD1.toByte() }),
                name = "Orphaned Contact", typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = null)
            val channel = ChannelDTO(radioId = radio, index = 1u, name = "Orphaned Channel",
                secret = Bytes(ByteArray(16)), unreadCount = 3, notificationLevel = NotificationLevel.MENTIONS_ONLY,
                isFavorite = true)
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Preserve me",
                timestamp = 1_700_000_000u, createdAt = at, deduplicationKey = "orphaned-message-source")
            val session = RemoteNodeSessionDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 0xCC.toByte() }),
                name = "TestNode", role = RemoteNodeRole.ROOM_SERVER)
            val roomMessage = RoomMessageDTO(sessionID = session.id, authorKeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 1),
                authorName = "TestAuthor", text = "Hello from the room", timestamp = 1_700_000_000u, createdAt = at)
            val blocked = BlockedChannelSenderDTO(UUID.randomUUID(), "SpammerNode", radio, at)
            store.saveDevice(device)
            store.saveContact(contact)
            store.saveChannel(channel)
            store.saveMessage(message)
            store.saveRemoteNodeSessionDTO(session)
            store.saveRoomMessage(radio, roomMessage)
            store.saveBlockedChannelSender(blocked)
            val path = store.createSavedTracePath(radio, "Test Path", Bytes.of(0x31, 0xA7), 1, null)
            store.deleteDevice(device.id)
            assertNull(store.fetchDevice(radio))
            val kinds = listOf(BackupModelKind.CONTACTS, BackupModelKind.CHANNELS, BackupModelKind.MESSAGES,
                BackupModelKind.REMOTE_NODE_SESSIONS, BackupModelKind.ROOM_MESSAGES,
                BackupModelKind.SAVED_TRACE_PATHS, BackupModelKind.BLOCKED_CHANNEL_SENDERS)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val parsed = AppBackupCodec().parseBackup(service.export(store).data)
            assertTrue(parsed.devices.isEmpty())
            assertTrue(parsed.manifest.validate(parsed))
            for (kind in kinds) assertEquals(1L, parsed.manifest.count(kind))
            withFreshDestination(backgroundScope) { destination ->
                val first = service.importBackup(parsed, destination)
                assertEquals(0L, first.counts.getValue(BackupModelKind.DEVICES).inserted)
                for (kind in kinds) assertEquals(1L, first.counts.getValue(kind).inserted)
                assertTrue(destination.fetchDevices().isEmpty())
                assertEquals("Orphaned Contact", destination.fetchContacts(radio).single().name)
                val restoredChannel = destination.fetchChannels(radio).single()
                assertEquals("Orphaned Channel", restoredChannel.name)
                assertEquals(3L, restoredChannel.unreadCount)
                assertEquals(NotificationLevel.MENTIONS_ONLY, restoredChannel.notificationLevel)
                assertTrue(restoredChannel.isFavorite)
                assertEquals("Preserve me", destination.fetchMessages(EntityKey(radio, contact.id)).single().text)
                val restoredSession = destination.fetchRemoteNodeSessions(radio).single()
                assertEquals(session.id, restoredSession.id)
                assertEquals("Hello from the room", destination.fetchRoomMessages(EntityKey(radio, session.id)).single().text)
                val restoredPath = destination.fetchSavedTracePaths(radio).single()
                assertEquals(path.id, restoredPath.id)
                assertEquals(Bytes.of(0x31, 0xA7), restoredPath.pathBytes)
                assertEquals(1L, restoredPath.hashSize)
                assertTrue(restoredPath.runs.isEmpty())
                assertEquals("SpammerNode", destination.fetchBlockedChannelSenders(radio).single().name)
                val second = service.importBackup(parsed, destination)
                assertEquals(0L, second.totalInserted)
                for (kind in kinds) assertEquals(1L, second.counts.getValue(kind).skipped)
            }
            assertNull(store.fetchDevice(radio))
            assertEquals(contact.id, store.fetchContacts(radio).single().id)
            assertEquals(channel.id, store.fetchChannels(radio).single().id)
            assertEquals(message.id, store.fetchMessages(EntityKey(radio, contact.id)).single().id)
            assertEquals(session.id, store.fetchRemoteNodeSessions(radio).single().id)
            assertEquals(roomMessage.id, store.fetchRoomMessages(EntityKey(radio, session.id)).single().id)
            assertEquals(path.id, store.fetchSavedTracePaths(radio).single().id)
            assertEquals(blocked.id, store.fetchBlockedChannelSenders(radio).single().id)
        } finally {
            storage.close()
        }
    }

    private suspend fun withFreshDestination(scope: CoroutineScope, block: suspend (RoomPersistenceStore) -> Unit) {
        val destinationDatabase = openMemoryDatabase()
        val destination = RoomPersistenceStore(destinationDatabase, scope)
        try {
            assertNotSame(db, destinationDatabase)
            assertTrue(destinationDatabase.devices().all().isEmpty())
            assertTrue(destinationDatabase.contacts().backupAll().isEmpty())
            assertTrue(destinationDatabase.messages().backupRadioIds().isEmpty())
            block(destination)
        } finally {
            try { destination.close() } finally { destinationDatabase.close() }
        }
    }
}
