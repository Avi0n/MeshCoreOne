// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/AppBackupServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Cross-component text families through actual codec, Room restore and process consumers.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.backup.*
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MessageBackupAcceptanceTest : RoomProcessTest() {
    @Test fun redactedRadioDefaultsUsePinnedValuesRatherThanTheCandidateAsOracle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val original = DeviceDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), nodeName = "Synthetic radio",
                blePin = 123_456u, frequency = 869_500u, bandwidth = 125_000u, spreadingFactor = 12u, codingRate = 8u,
                txPower = 14, maxTxPower = 22, latitude = 48.8566, longitude = 2.3522,
            )
            store.saveDevice(original)
            val exported = AppBackupCodec().parseBackup(AppBackupService(storage.backupPreferences, "synthetic", "1").export(store).data)
                .devices.single()
            assertNotEquals(original.id, exported.id)
            assertEquals(original.publicKey, exported.publicKey)
            assertEquals(0u, exported.blePin)
            assertEquals(915_000u, exported.frequency)
            assertEquals(250_000u, exported.bandwidth)
            assertEquals(10.toUByte(), exported.spreadingFactor)
            assertEquals(5.toUByte(), exported.codingRate)
            assertEquals(20.toByte(), exported.txPower)
            assertEquals(20.toByte(), exported.maxTxPower)
            assertEquals(0.0, exported.latitude, 0.0)
            assertEquals(0.0, exported.longitude, 0.0)
            assertEquals(original, store.fetchDevice(radio))
        } finally {
            storage.close()
        }
    }

    @Test fun cjkOriginalTextSurvivesBackupAndMountedTimeline() = roundTrip("\u4F60\u597D\uFF0C\u4E16\u754C")
    @Test fun rtlOriginalTextSurvivesBackupAndMountedTimeline() = roundTrip("\u05E9\u05DC\u05D5\u05DD 123 \u0645\u0631\u062D\u0628\u0627")
    @Test fun zwjOriginalTextSurvivesBackupAndMountedTimeline() = roundTrip("\uD83D\uDC69\u200D\uD83D\uDCBB\uD83C\uDFFD")
    @Test fun decomposedOriginalTextSurvivesBackupAndMountedTimeline() = roundTrip("Cafe\u0301")
    @Test fun composedOriginalTextSurvivesBackupAndMountedTimeline() = roundTrip("Caf\u00E9")
    @Test fun whitespaceAndQuoteOriginalTextSurvivesBackupAndMountedTimeline() =
        roundTrip("  > quoted\n\toriginal https://example.invalid/?a=1&b=2  ")

    private fun roundTrip(text: String) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val harness = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val contact = ContactDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }), name = "Synthetic peer",
                lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            val first = MessageDTO(
                radioId = radio, contactID = contact.id, text = text, timestamp = UInt.MAX_VALUE,
                createdAt = Instant.ofEpochSecond(1_700_000_000, 125_000_000), status = MessageStatus.SENT,
            )
            val second = first.copy(id = UUID.randomUUID(), replyToID = first.id)
            store.saveMessage(first)
            store.saveMessage(second)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            val codec = AppBackupCodec()
            val exported = service.export(store)
            val parsed = codec.parseBackup(exported.data)
            assertEquals(2L, parsed.manifest.messageCount)
            assertEquals(setOf(first.id, second.id), parsed.messages.map { it.id }.toSet())
            assertTrue(parsed.messages.all { it.text.toByteArray(Charsets.UTF_8).contentEquals(text.toByteArray(Charsets.UTF_8)) })
            assertTrue(parsed.messages.all { it.timestamp == UInt.MAX_VALUE && it.createdAt == first.createdAt })
            assertTrue(parsed.messages.all { it.deduplicationKey == null })
            store.deleteMessage(EntityKey(radio, second.id))
            store.deleteMessage(EntityKey(radio, first.id))
            val restored = service.importBackup(parsed, store)
            assertEquals(2L, restored.counts.getValue(BackupModelKind.MESSAGES).inserted)
            assertEquals(first.id, requireNotNull(store.fetchMessage(EntityKey(radio, second.id))).replyToID)
            assertEquals(0L, service.importBackup(parsed, store).totalInserted)
            val conversation = TimelineConversation.Direct(contact)
            val timeline = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), backgroundScope)
            timeline.start()
            timeline.open()
            harness.eventually("both restored original messages visible") { timeline.state.value.messages.size == 2 }
            assertEquals(setOf(first.id, second.id), timeline.state.value.messages.map { it.id }.toSet())
            assertTrue(timeline.state.value.messages.all { it.text == text })
            assertEquals(0, harness.lifecycle.created.size)
            assertTrue(harness.links.links.isEmpty())
        } finally {
            harness.container.close()
            storage.close()
        }
    }

    @Test fun swiftProducedFixtureReachesTheOfflineTimelineWithOriginalBytes() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        val harness = ContainerHarness(this, store)
        try {
            val fixture = java.io.File(requireNotNull(System.getProperty("referenceCodecFixtures")))
                .resolve("reference-envelope.meshcoreone").readBytes()
            assertEquals(1835, fixture.size)
            assertEquals("32e5898f8254eed7ca2744e5206698c00c95e5a1ee8767ece187b8599a012ffc",
                java.security.MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) })
            val parsed = AppBackupCodec().parseBackup(Bytes(fixture))
            val result = AppBackupService(storage.backupPreferences, "synthetic", "1").importBackup(parsed, store)
            assertEquals(1L, result.counts.getValue(BackupModelKind.MESSAGES).inserted)
            val radio = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            val contact = store.fetchContacts(radio).single()
            assertEquals(Bytes.of(0, 0x80, 0xFF), contact.avatarImageData)
            val restored = requireNotNull(store.fetchMessage(EntityKey(radio,
                UUID.fromString("00000000-0000-0000-0000-000000000004"))))
            val original = "Hi\u4F60\uD83D\uDE00\u05E9\u05DC\u05D5\u05DD"
            assertArrayEquals(original.toByteArray(Charsets.UTF_8), restored.text.toByteArray(Charsets.UTF_8))
            assertEquals(UInt.MAX_VALUE, restored.timestamp)
            assertEquals(Instant.ofEpochSecond(1_700_000_501, 123_456_700), restored.createdAt)
            val conversation = TimelineConversation.Direct(contact)
            val timeline = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), backgroundScope)
            timeline.start()
            timeline.open()
            harness.eventually("actual Swift-produced message visible") { timeline.state.value.messages.size == 1 }
            assertEquals(restored.id, timeline.state.value.messages.single().id)
            assertEquals(original, timeline.state.value.messages.single().text)
            assertEquals(0, harness.lifecycle.created.size)
            assertTrue(harness.links.links.isEmpty())
        } finally {
            harness.container.close()
            storage.close()
        }
    }

    @Test fun corruptedManifestCannotMutateTheProcessStore() = runTest {
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        try {
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 7 }),
                name = "Must not be inserted", lastHeardTimestamp = null)
            val value = AppBackupEnvelope(exportDate = Instant.EPOCH, appVersion = "synthetic", appBuild = "1",
                contacts = listOf(contact).snapshot())
            val failure = assertThrows(AppBackupException::class.java) { AppBackupCodec().parseBackup(AppBackupCodec().encode(value)) }
            assertEquals(AppBackupError.CorruptedManifest, failure.error)
            val service = AppBackupService(storage.backupPreferences, "synthetic", "1")
            try {
                service.importBackup(value, store)
                fail("Invalid manifest must not reach the restore transaction")
            } catch (expected: AppBackupException) {
                assertEquals(AppBackupError.CorruptedManifest, expected.error)
            }
            assertTrue(store.fetchContacts(contact.radioId).isEmpty())
            assertEquals(1L, service.importBackup(value.withActualManifest(), store).totalInserted)
            assertEquals(contact.name, requireNotNull(store.fetchContact(EntityKey(contact.radioId, contact.id))).name)
        } finally { storage.close() }
    }

    @Test fun cancelledCodecClosesInputAndDoesNotBecomeInvalidFileSuccess() {
        val envelope = AppBackupEnvelope(exportDate = Instant.EPOCH, appVersion = "synthetic", appBuild = "1")
        var closed = false
        val input = object : java.io.ByteArrayInputStream(AppBackupCodec().encode(envelope).toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val cancellation = CancellationException("Synthetic cancellation")
        val thrown = assertThrows(CancellationException::class.java) {
            AppBackupCodec().parseBackup(input, checkCancellation = { throw cancellation })
        }
        assertSame(cancellation, thrown)
        assertTrue(closed)
    }
}
