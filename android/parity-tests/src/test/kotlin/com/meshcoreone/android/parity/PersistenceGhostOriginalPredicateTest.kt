// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceGhostOriginalPredicateTest : RoomProcessTest() {
    private fun device(keyByte: Int = 0x44): DeviceDTO {
        val id = UUID.randomUUID()
        return DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { keyByte.toByte() }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity is a no-op when current device already owns the publicKey()")
    @Test fun originalAlreadyOwnedKeyRetainsNullAndOptionalOriginalRadioReadback() = runTest {
        val device = device()
        store.saveDevice(device)

        val result = store.reconcileGhostIdentity(device.id, device.publicKey)
        assertNull(result)
        val unchanged = store.fetchDevice(device.id)
        assertEquals(device.radioId, unchanged?.radioId)
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity returns nil when no ghost matches()")
    @Test fun originalUnrelatedKeyRetainsNullAndBothOriginalKeyAndRadioReadbacks() = runTest {
        val device = device()
        store.saveDevice(device)
        val unrelatedKey = Bytes(ByteArray(32) { 0x45 })

        assertNull(store.reconcileGhostIdentity(device.id, unrelatedKey))
        val unchanged = store.fetchDevice(device.id)
        assertEquals(device.publicKey, unchanged?.publicKey)
        assertEquals(device.radioId, unchanged?.radioId)
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity refuses to delete a saved-but-inactive device with BLE methods()")
    @Test fun originalInactiveBluetoothDeviceRetainsNullSurvivingOriginalIdAndBluetoothMembership() = runTest {
        val sharedPublicKey = Bytes(ByteArray(32) { 0x45 })
        val inactive = device(0x45).copy(
            isActive = false, connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())),
        )
        store.saveDevice(inactive)
        val current = device().copy(isActive = true)
        store.saveDevice(current)

        assertNull(store.reconcileGhostIdentity(current.id, sharedPublicKey))
        val stillThere = store.fetchDevice(inactive.id)
        assertNotNull(stillThere)
        assertEquals(true, stillThere.connectionMethods.any { it.isBluetooth })
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity finds ghost even when current device's publicKey already matches()")
    @Test fun originalSameKeyRetryRetainsGhostRadioCurrentIdRewriteAndGhostIdAbsence() = runTest {
        val restoredPublicKey = Bytes(ByteArray(32) { 0x45 })
        val ghostRadio = RadioId(UUID.randomUUID())
        val ghost = device(0x45).copy(
            radioId = ghostRadio, isActive = false, connectionMethods = SnapshotList.empty(),
        )
        store.saveDevice(ghost)
        val staleRadio = RadioId(UUID.randomUUID())
        val current = device(0x45).copy(radioId = staleRadio, isActive = true)
        store.saveDevice(current)

        assertEquals(ghostRadio, store.reconcileGhostIdentity(current.id, restoredPublicKey))
        val updated = store.fetchDevice(current.id)
        assertEquals(ghostRadio, updated?.radioId)
        assertNull(store.fetchDevice(ghost.id))
    }

    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity preserves non-BLE methods from backup ghost()")
    @Test fun originalBackupWifiRetainsRequiredCurrentBothFullMethodsExactlyOneBluetoothAndGhostAbsence() = runTest {
        val restoredPublicKey = Bytes(ByteArray(32) { 0x45 })
        val backupWifi = ConnectionMethod.WiFi("10.0.0.7", 5000u, "Backup WiFi")
        val ghostRadio = RadioId(UUID.randomUUID())
        val ghost = device(0x45).copy(
            radioId = ghostRadio, isActive = false, connectionMethods = SnapshotList.of(backupWifi),
        )
        store.saveDevice(ghost)
        val currentBluetooth = ConnectionMethod.Bluetooth(UUID.randomUUID(), "Current BLE")
        val current = device().copy(
            radioId = RadioId(UUID.randomUUID()), isActive = true,
            connectionMethods = SnapshotList.of(currentBluetooth),
        )
        store.saveDevice(current)

        assertEquals(ghostRadio, store.reconcileGhostIdentity(current.id, restoredPublicKey))
        val updated = assertNotNull(store.fetchDevice(current.id))
        assertTrue(updated.connectionMethods.contains(currentBluetooth))
        assertTrue(updated.connectionMethods.contains(backupWifi))
        assertEquals(1, updated.connectionMethods.count { it.isBluetooth })
        assertNull(store.fetchDevice(ghost.id))
    }
}
