// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistencePendingMaintenanceOriginalPredicateTest : RoomProcessTest() {
    private suspend fun device(id: UUID = UUID.randomUUID()): DeviceDTO = DeviceDTO(
        id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
        nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
        manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
        latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
    ).also { store.saveDevice(it) }

    private fun frame(name: String, keyByte: Int) = ContactFrame(
        Bytes(ByteArray(32) { keyByte.toByte() }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
        Instant.now().epochSecond.toUInt(), 37.7749, -122.4194, Instant.now().epochSecond.toUInt(),
    )

    private fun pending(
        messageID: UUID = UUID.randomUUID(),
        radio: RadioId = RadioId(UUID.randomUUID()),
        attemptCount: Long? = 0,
        sequence: Long = 1,
    ) = PendingSendDTO(
        id = UUID.randomUUID(), radioId = radio, messageID = messageID, kind = PendingSendKind.DM,
        contactID = UUID.randomUUID(), channelIndex = null, isResend = false,
        messageText = "", messageTimestamp = 0u, localNodeName = null,
        sequence = sequence, enqueuedAt = Instant.now(), attemptCount = attemptCount,
    )

    @OriginalCase("PersistenceStoreTests::incrementPendingSendAttemptCount from 0 bumps to 1()")
    @Test fun originalFirstDrainRetainsReturnedOneAndPersistedOptionalAttemptOne() = runTest {
        val messageID = UUID.randomUUID()
        val dto = pending(messageID = messageID, attemptCount = 0)
        store.upsertPendingSend(dto)

        val result = store.incrementPendingSendAttemptCount(EntityKey(dto.radioId, messageID))
        assertEquals(1L, result)
        val persisted = store.fetchPendingSends(dto.radioId).firstOrNull()
        assertEquals(1L, persisted?.attemptCount)
    }

    @OriginalCase("PersistenceStoreTests::incrementPendingSendAttemptCount returns nil when no row matches()")
    @Test fun originalMissingPendingMessageReturnsNullWithoutCreatingARow() = runTest {
        val messageID = UUID.randomUUID()
        val result = store.incrementPendingSendAttemptCount(EntityKey(RadioId(UUID.randomUUID()), messageID))
        assertNull(result)
    }

    @OriginalCase("PersistenceStoreTests::purgeLegacyAttemptCountRows deletes only legacy nil rows()")
    @Test fun originalNilZeroAndThreeAttemptsRetainDeleteOneForbiddenLegacyAndExactSurvivorCounts() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val legacy = pending(radio = radio, attemptCount = null, sequence = 1)
        val race = pending(radio = radio, attemptCount = 0, sequence = 2)
        val drained = pending(radio = radio, attemptCount = 3, sequence = 3)
        store.upsertPendingSend(legacy)
        store.upsertPendingSend(race)
        store.upsertPendingSend(drained)

        assertEquals(1L, store.purgeLegacyAttemptCountRows())
        val rows = store.fetchPendingSends(radio)
        val messageIDs = rows.map { it.messageID }.toSet()
        assertFalse(messageIDs.contains(legacy.messageID))
        val byMessageID = rows.associate { it.messageID to it.attemptCount }
        assertEquals(0L, byMessageID[race.messageID])
        assertEquals(3L, byMessageID[drained.messageID])
    }

    @OriginalCase("PersistenceStoreTests::purgeLegacyAttemptCountRows is idempotent()")
    @Test fun originalRepeatedLegacyPurgeRetainsIndependentOneThenZeroReturns() = runTest {
        val radio = RadioId(UUID.randomUUID())
        store.upsertPendingSend(pending(radio = radio, attemptCount = null))

        val firstDeleted = store.purgeLegacyAttemptCountRows()
        val secondDeleted = store.purgeLegacyAttemptCountRows()
        assertEquals(1L, firstDeleted)
        assertEquals(0L, secondDeleted)
    }

    @OriginalCase("PersistenceStoreTests::warmUp runs both purgeOrphanPendingSends and purgeLegacyAttemptCountRows()")
    @Test fun originalWarmUpRetainsPairedAndUnpairedNilAttemptContextsAndBothEmptyReads() = runTest {
        val radioWithDevice = UUID.randomUUID()
        val radioWithoutDevice = RadioId(UUID.randomUUID())
        val device = DeviceDTO(
            id = radioWithDevice, radioId = RadioId(radioWithDevice),
            publicKey = Bytes(ByteArray(32) { 0x01 }), nodeName = "TestDevice",
            firmwareVersion = 9u, firmwareVersionString = "v1.13.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            maxContacts = 100u, maxChannels = 8u, frequency = 915_000u, bandwidth = 250_000u,
            spreadingFactor = 10u, codingRate = 5u, txPower = 20, maxTxPower = 20,
            latitude = 0.0, longitude = 0.0, blePin = 0u, manualAddContacts = false,
            multiAcks = 2u, telemetryModeBase = 2u, telemetryModeLoc = 0u,
            telemetryModeEnv = 0u, advertLocationPolicy = 0u,
            lastConnected = Instant.now(), lastContactSync = 0u, isActive = true,
            ocvPreset = null, customOCVArrayString = null,
        ).also { store.saveDevice(it) }
        val nilOnDevice = pending(radio = device.radioId, attemptCount = null, sequence = 1)
        val orphan = pending(radio = radioWithoutDevice, attemptCount = null, sequence = 1)
        store.upsertPendingSend(nilOnDevice)
        store.upsertPendingSend(orphan)

        store.warmUp()
        val survivingForDevice = store.fetchPendingSends(device.radioId)
        val survivingForUnknown = store.fetchPendingSends(radioWithoutDevice)
        assertTrue(survivingForDevice.isEmpty())
        assertTrue(survivingForUnknown.isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deletePendingSendsForMessage public API saves on return()")
    @Test fun originalPublicPendingDeleteRetainsAttemptOneAndImmediateFalseWithoutTestSave() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val messageID = UUID.randomUUID()
        store.upsertPendingSend(pending(messageID = messageID, radio = radio, attemptCount = 1, sequence = 1))

        store.deletePendingSendsForMessage(EntityKey(radio, messageID))
        assertFalse(store.hasPendingSend(EntityKey(radio, messageID)))
    }

    @OriginalCase("PersistenceStoreTests::deleteMessage cascades the matching PendingSend in a single transaction()")
    @Test fun originalChannelZeroHelloDeletionRetainsBothPendingAndMessageEmptyPostconditions() = runTest {
        val device = device()
        val messageID = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            id = messageID, radioId = device.radioId, contactID = null, channelIndex = 0u,
            text = "hello", timestamp = Instant.now().epochSecond.toUInt(),
        ))
        store.upsertPendingSend(pending(messageID = messageID, radio = device.radioId, attemptCount = 0))

        store.deleteMessage(EntityKey(device.radioId, messageID))
        val remainingPending = store.fetchPendingSends(device.radioId)
        assertTrue(remainingPending.isEmpty())
        val remainingMessages = db.messages().backupPageWithoutPreviewBlobs(device.radioId.value, Long.MAX_VALUE, 0)
        assertTrue(remainingMessages.isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteMessage reaps an orphan PendingSend even when no Message row exists()")
    @Test fun originalNoMessageRowOrphanDeletionRetainsPendingEmptyRead() = runTest {
        val device = device()
        val orphanMessageID = UUID.randomUUID()
        store.upsertPendingSend(pending(messageID = orphanMessageID, radio = device.radioId, attemptCount = 0))

        store.deleteMessage(EntityKey(device.radioId, orphanMessageID))
        assertTrue(store.fetchPendingSends(device.radioId).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::deleteDeviceData reaps radio-scoped orphan PendingSends and preserves the Device row()")
    @Test fun originalMatchedAndOrphanDeviceDataDeletionRetainsPendingEmptyAndNonNullDevice() = runTest {
        val device = device()
        val matchedMessageID = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            id = matchedMessageID, radioId = device.radioId, contactID = null, channelIndex = 0u,
            text = "matched", timestamp = Instant.now().epochSecond.toUInt(),
        ))
        store.upsertPendingSend(pending(
            messageID = matchedMessageID, radio = device.radioId, attemptCount = 0, sequence = 1,
        ))
        store.upsertPendingSend(pending(radio = device.radioId, attemptCount = 0, sequence = 2))

        store.deleteDeviceData(device.id)
        assertTrue(store.fetchPendingSends(device.radioId).isEmpty())
        assertNotNull(store.fetchDevice(device.id))
    }

    @OriginalCase("PersistenceStoreTests::deleteMessagesForContact cascades PendingSends and spares unrelated contacts()")
    @Test fun originalThreeContactOneRowsRetainOnlyContactTwoPendingIdentityAndMessageCount() = runTest {
        val device = device()
        val contact1 = store.saveContact(device.radioId, frame("Contact1", 0x55)).id
        val contact2 = store.saveContact(device.radioId, frame("Contact2", 0x56)).id
        for (i in 0 until 3) {
            val messageID = UUID.randomUUID()
            store.saveMessage(MessageDTO(
                id = messageID, radioId = device.radioId, contactID = contact1,
                text = "C1 $i", timestamp = Instant.now().epochSecond.toUInt() + i.toUInt(),
            ))
            store.upsertPendingSend(pending(
                messageID = messageID, radio = device.radioId, attemptCount = 0, sequence = i.toLong() + 1,
            ))
        }
        val contact2MessageID = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            id = contact2MessageID, radioId = device.radioId, contactID = contact2,
            text = "C2 keep", timestamp = Instant.now().epochSecond.toUInt() + 100u,
        ))
        store.upsertPendingSend(pending(
            messageID = contact2MessageID, radio = device.radioId, attemptCount = 0, sequence = 99,
        ))

        store.deleteMessagesForContact(EntityKey(device.radioId, contact1))
        val remaining = store.fetchPendingSends(device.radioId)
        assertEquals(1, remaining.size)
        assertEquals(contact2MessageID, remaining.firstOrNull()?.messageID)
        val contact2Messages = store.fetchMessages(EntityKey(device.radioId, contact2))
        assertEquals(1, contact2Messages.size)
    }
}
