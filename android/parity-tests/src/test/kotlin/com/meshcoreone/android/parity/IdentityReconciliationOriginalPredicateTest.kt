// PortedFrom: MC1Services/Tests/MC1ServicesTests/IdentityReconciliationIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IdentityReconciliationOriginalPredicateTest : RoomProcessTest() {
    @Test fun originalBobFrameRelinksAfterGhostDemotionAndRePair() = runTest {
        val original = sourceDevice(key(0))
        store.saveDevice(original)
        assertEquals(original, store.fetchDevice(original.id))
        val bob = saveSourceContact(original.radioId, "Bob", key(64))
        val ghost = demote(original)
        val paired = sourceDevice(key(128))
        store.saveDevice(paired)
        assertEquals(paired, store.fetchDevice(paired.id))
        assertTrue(store.fetchContacts(paired.radioId).isEmpty())
        assertEquals(1, store.fetchContacts(original.radioId).size)
        assertEquals(listOf(bob), store.fetchContacts(original.radioId).toList())

        val reconciled = store.reconcileGhostIdentity(paired.id, original.publicKey)

        assertEquals(original.radioId, reconciled)
        val updated = requireNotNull(store.fetchDevice(paired.id))
        assertEquals(original.radioId, updated.radioId)
        assertEquals(original.publicKey, updated.publicKey)
        assertEquals(paired.copy(radioId = original.radioId, publicKey = original.publicKey), updated)
        assertNull(store.fetchDevice(original.id))
        assertNull(store.fetchDevice(ghost.id))
        val linked = store.fetchContacts(original.radioId)
        assertEquals(1, linked.size)
        assertEquals("Bob", linked.firstOrNull()?.name)
        assertEquals(listOf(bob), linked.toList())
        assertEquals(bob, store.fetchContact(EntityKey(original.radioId, bob.id)))
    }

    @Test fun originalUnchangedPublicKeyKeepsTheCompleteSourceDevice() = runTest {
        val original = sourceDevice(key(0))
        store.saveDevice(original)
        assertEquals(original, store.fetchDevice(original.id))

        val result = store.reconcileGhostIdentity(original.id, original.publicKey)

        assertNull(result)
        val unchanged = requireNotNull(store.fetchDevice(original.id))
        assertEquals(original.radioId, unchanged.radioId)
        assertEquals(original.publicKey, unchanged.publicKey)
        assertEquals(original, unchanged)
        assertEquals(listOf(original), store.fetchDevices().toList())
    }

    @Test fun originalPostPairZeroFrameStaysUnderTheTemporaryRadio() = runTest {
        val original = sourceDevice(key(0))
        store.saveDevice(original)
        assertEquals(original, store.fetchDevice(original.id))
        val ghost = demote(original)
        val paired = sourceDevice(key(64))
        store.saveDevice(paired)
        assertEquals(paired, store.fetchDevice(paired.id))
        val postPair = saveSourceContact(paired.radioId, "PostPair", key(128))
        assertTrue(store.fetchContacts(original.radioId).isEmpty())

        val reconciled = store.reconcileGhostIdentity(paired.id, original.publicKey)

        assertEquals(original.radioId, reconciled)
        val underNew = store.fetchContacts(paired.radioId)
        assertEquals(1, underNew.size)
        assertEquals("PostPair", underNew.firstOrNull()?.name)
        assertEquals(listOf(postPair), underNew.toList())
        assertEquals(postPair, store.fetchContact(EntityKey(paired.radioId, postPair.id)))
        assertTrue(store.fetchContacts(original.radioId).isEmpty())
        val updated = requireNotNull(store.fetchDevice(paired.id))
        assertEquals(original.radioId, updated.radioId)
        assertEquals(original.publicKey, updated.publicKey)
        assertEquals(paired.copy(radioId = original.radioId, publicKey = original.publicKey), updated)
        assertNull(store.fetchDevice(ghost.id))
    }

    private fun key(offset: Int) = Bytes(ByteArray(32) { (offset + it).toByte() })

    private fun sourceDevice(publicKey: Bytes) = DeviceDTO(
        id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = publicKey,
        nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
        manufacturerName = "TestMfg", buildDate = "01 Jan 2025", maxContacts = 100u, maxChannels = 8u,
        frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 10u, codingRate = 5u,
        txPower = 20, maxTxPower = 20, latitude = 0.0, longitude = 0.0, blePin = 0u,
        manualAddContacts = false, multiAcks = 0u, telemetryModeBase = 2u,
        telemetryModeLoc = 0u, telemetryModeEnv = 0u, advertLocationPolicy = 0u,
        lastConnected = Instant.ofEpochSecond(1_704_067_200), lastContactSync = 0u, isActive = true,
    )

    private suspend fun demote(original: DeviceDTO): DeviceDTO {
        store.demoteDeviceToGhost(original.id)
        assertNull(store.fetchDevice(original.id))
        val ghost = requireNotNull(store.fetchDevice(original.publicKey))
        assertNotEquals(original.id, ghost.id)
        assertEquals(original.copy(id = ghost.id, isActive = false), ghost)
        return ghost
    }

    private suspend fun saveSourceContact(radio: RadioId, name: String, publicKey: Bytes): ContactDTO {
        val frame = ContactFrame(publicKey, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, 0u, 0.0, 0.0, 0u)
        val saved = store.saveContact(radio, frame)
        assertTrue(saved.isNew)
        val expected = ContactDTO(
            id = saved.id, radioId = radio, publicKey = publicKey, name = name, typeRawValue = 1u,
            flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY, lastAdvertTimestamp = 0u,
            latitude = 0.0, longitude = 0.0, lastModified = 0u, lastHeardTimestamp = 0u,
        )
        assertEquals(expected, store.fetchContact(radio, publicKey))
        assertEquals(listOf(expected), store.fetchContacts(radio).toList())
        return expected
    }
}
