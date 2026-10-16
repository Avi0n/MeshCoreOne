package one.meshcore.android

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity

internal object ConnectionServiceIntentRadio {
    val DEVICE: Uuid = Uuid.normalized("A1000000-0000-4000-8000-000000000001")
    val RADIO: Uuid = Uuid.normalized("A2000000-0000-4000-8000-000000000002")
    const val NAME = "Service fixture"
    const val HANDLE = "A3000000-0000-4000-8000-000000000003"
    private const val KEY_LENGTH = 32
    private const val KEY_FILL = 0x31

    fun entity(): DeviceEntity = DeviceEntity(
        id = DEVICE,
        radioID = RADIO,
        publicKey = ByteArray(KEY_LENGTH) { KEY_FILL.toByte() },
        nodeName = NAME,
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
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
        isActive = true,
        connectionMethods = listOf(ConnectionMethod.Bluetooth(Uuid.normalized(HANDLE), NAME)),
    )
}
