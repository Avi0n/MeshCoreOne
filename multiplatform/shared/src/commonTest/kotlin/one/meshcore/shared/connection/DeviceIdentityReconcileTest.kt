package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceIdentityReconcileTest {
    private val liveId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val liveRadio = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
    private val ghostId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
    private val ghostRadio = Uuid.normalized("D0000000-0000-0000-0000-000000000001")
    private val oldKey = ByteArray(32) { 0x11 }
    private val newKey = ByteArray(32) { 0x22 }

    @Test
    fun rewritesPublicKeyPreservingRadioId() {
        val live = device(DeviceSeed(id = liveId, radioID = liveRadio, publicKey = oldKey, isActive = true))
        val result = reconcileDeviceIdentity(
            devices = listOf(live),
            currentDeviceId = liveId,
            currentRadioId = liveRadio,
            newPublicKey = newKey,
            newNodeName = "Restored",
        )
        assertEquals(liveRadio, result.radioID)
        assertNull(result.deleteDeviceId)
        val upsert = assertNotNull(result.upsert)
        assertContentEquals(newKey, upsert.publicKey)
        assertEquals(liveRadio, upsert.radioID)
        assertEquals("Restored", upsert.nodeName)
    }

    @Test
    fun adoptsGhostRadioIdAndDeletesGhost() {
        val live = device(DeviceSeed(id = liveId, radioID = liveRadio, publicKey = oldKey, isActive = true))
        val ghost = device(
            DeviceSeed(
                id = ghostId,
                radioID = ghostRadio,
                publicKey = newKey,
                isActive = false,
                methods = listOf(ConnectionMethod.WiFi(host = "10.0.0.1", port = 5000, displayName = null)),
            ),
        )
        val result = reconcileDeviceIdentity(
            devices = listOf(live, ghost),
            currentDeviceId = liveId,
            currentRadioId = liveRadio,
            newPublicKey = newKey,
            newNodeName = "Ghost",
        )
        assertEquals(ghostRadio, result.radioID)
        assertEquals(ghostId, result.deleteDeviceId)
        val upsert = assertNotNull(result.upsert)
        assertEquals(ghostRadio, upsert.radioID)
        assertContentEquals(newKey, upsert.publicKey)
        assertEquals(1, upsert.connectionMethods.size)
    }

    @Test
    fun refusesGhostThatStillHasBluetooth() {
        val live = device(DeviceSeed(id = liveId, radioID = liveRadio, publicKey = oldKey, isActive = true))
        val ghost = device(
            DeviceSeed(
                id = ghostId,
                radioID = ghostRadio,
                publicKey = newKey,
                isActive = false,
                methods = listOf(
                    ConnectionMethod.Bluetooth(
                        peripheralUuid = Uuid.normalized("E0000000-0000-0000-0000-000000000001"),
                        displayName = "Old",
                    ),
                ),
            ),
        )
        val result = reconcileDeviceIdentity(
            devices = listOf(live, ghost),
            currentDeviceId = liveId,
            currentRadioId = liveRadio,
            newPublicKey = newKey,
            newNodeName = "N",
        )
        // Bluetooth ghost is not adopted — rewrite live public key only.
        assertEquals(liveRadio, result.radioID)
        assertNull(result.deleteDeviceId)
        assertNotNull(result.upsert)
        assertContentEquals(newKey, result.upsert!!.publicKey)
    }

    @Test
    fun noOpWhenKeyUnchanged() {
        val live = device(
            DeviceSeed(id = liveId, radioID = liveRadio, publicKey = newKey, isActive = true, name = "Same"),
        )
        val result = reconcileDeviceIdentity(
            devices = listOf(live),
            currentDeviceId = liveId,
            currentRadioId = liveRadio,
            newPublicKey = newKey,
            newNodeName = "Same",
        )
        assertEquals(liveRadio, result.radioID)
        assertNull(result.upsert)
        assertNull(result.deleteDeviceId)
    }

    private data class DeviceSeed(
        val id: Uuid,
        val radioID: Uuid,
        val publicKey: ByteArray,
        val isActive: Boolean,
        val name: String = "Node",
        val methods: List<ConnectionMethod> = emptyList(),
    )

    private fun device(seed: DeviceSeed): DeviceEntity = DeviceEntity(
        id = seed.id,
        radioID = seed.radioID,
        publicKey = seed.publicKey,
        nodeName = seed.name,
        firmwareVersion = 1,
        firmwareVersionString = "1",
        manufacturerName = "t",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = seed.isActive,
        connectionMethods = seed.methods,
    )
}
