// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreBatchSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/PersistenceStore+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatchChannelReconciliationOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())

    @Before fun seedOriginalDevice() = runBlocking {
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.0.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            maxContacts = 100u, maxChannels = 8u, multiAcks = 0u,
            lastConnected = Instant.ofEpochSecond(1_704_067_200), lastContactSync = 0u, isActive = true,
        )
        store.saveDevice(device)
        assertEquals(device, store.fetchDevice(radio))
    }

    @Test fun batchUpsertRequiresOriginalUpdatedAndInsertedPersistedRows() = runTest {
        seed(info(1u, "Old", 0x11))

        val result = batch(SnapshotList.of(info(1u, "New", 0x99), info(2u, "Two", 0x22)))

        assertIndices(result, listOf(1u, 2u))
        requireChannel(info(1u, "New", 0x99))
        requireChannel(info(2u, "Two", 0x22))
    }

    @Test fun unconfiguredOriginalDeletesOnlyConfirmedStaleSlot() = runTest {
        seed(info(1u, "Keep", 0x11), info(2u, "Gone", 0x22))

        val result = batch(SnapshotList.of(info(1u, "Keep", 0x11)), SnapshotList.of(2u))

        assertIndices(result, listOf(1u))
        assertNull(store.fetchChannel(radio, 2u))
        requireChannel(info(1u, "Keep", 0x11))
    }

    @Test fun capacityOriginalPrunesSlotTenAndPreservesInRange() = runTest {
        seed(info(1u, "InRange", 0x11), info(10u, "Orphan", 0xAA))

        val result = batch(SnapshotList.of(info(1u, "InRange", 0x11)))

        assertIndices(result, listOf(1u))
        assertNull(store.fetchChannel(radio, 10u))
        requireChannel(info(1u, "InRange", 0x11))
    }

    @Test fun circuitBreakerOriginalKeepsBothUnqueriedSlotsTwoAndThree() = runTest {
        seed(info(1u, "One", 0x11), info(2u, "Two", 0x22), info(3u, "Three", 0x33))

        val result = batch(SnapshotList.of(info(1u, "OneUpdated", 0x11)))

        assertIndices(result, listOf(1u, 2u, 3u))
        requireChannel(info(1u, "OneUpdated", 0x11))
        requireChannel(info(2u, "Two", 0x22))
        requireChannel(info(3u, "Three", 0x33))
    }

    @Test fun nonContiguousOriginalUsesAllSourceSlotsSecretsAndRequiredFetch() = runTest {
        val result = batch(
            SnapshotList.of(info(0u, "Public", 0x00), info(2u, "Two", 0x22), info(7u, "Seven", 0x77)),
            SnapshotList.of(1u, 3u, 4u, 5u, 6u),
        )

        assertIndices(result, listOf(0u, 2u, 7u))
        requireChannel(info(0u, "Public", 0x00))
        requireChannel(info(2u, "Two", 0x22))
        requireChannel(info(7u, "Seven", 0x77))
    }

    @Test fun radioScopeOriginalRetainsOtherRadioAtSameSlotOne() = runTest {
        val otherRadio = RadioId(UUID.randomUUID())
        val other = info(1u, "OtherRadio", 0x55)
        store.saveChannel(otherRadio, other)
        requireChannel(other, otherRadio)

        batch(SnapshotList.of(info(1u, "Mine", 0x11)))

        requireChannel(other, otherRadio)
        requireChannel(info(1u, "Mine", 0x11))
    }

    private fun secret(byte: Int) = Bytes(ByteArray(16) { byte.toByte() })

    private fun info(index: UByte, name: String, secretByte: Int) = ChannelInfo(index, name, secret(secretByte))

    private suspend fun seed(vararg channels: ChannelInfo) {
        for (channel in channels) store.saveChannel(radio, channel)
        for (channel in channels) requireChannel(channel)
    }

    private suspend fun batch(
        configured: SnapshotList<ChannelInfo>,
        unconfigured: SnapshotList<UByte> = SnapshotList.empty(),
    ): SnapshotList<ChannelDTO> = store.batchSaveChannels(radio, configured, unconfigured, 8u)

    private fun assertIndices(rows: SnapshotList<ChannelDTO>, indices: List<UByte>) {
        assertEquals(indices, rows.map { it.index })
        assertTrue(rows.all { it.radioId == radio })
    }

    private suspend fun requireChannel(expected: ChannelInfo, radioId: RadioId = radio) {
        val actual = requireNotNull(store.fetchChannel(radioId, expected.index))
        assertEquals(radioId, actual.radioId)
        assertEquals(expected.index, actual.index)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.secret, actual.secret)
    }
}
