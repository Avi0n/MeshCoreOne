// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Export through public repositories into a distinct empty Room process store.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.ContainerHarness
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
