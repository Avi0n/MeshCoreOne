package one.meshcore.shared.backup

/**
 * Radio and location defaults a device is reset to by [DeviceDto.redactedForBackup]. Mirrors Swift
 * `Device.Defaults`, which is also what `Device.init` seeds, so the two paths cannot drift.
 */
object DeviceBackupDefaults {
    const val FREQUENCY: Long = 915_000
    const val BANDWIDTH: Long = 250_000
    const val SPREADING_FACTOR: Int = 10
    const val CODING_RATE: Int = 5
    const val TX_POWER: Int = 20
    const val MAX_TX_POWER: Int = 20
    const val LATITUDE: Double = 0.0
    const val LONGITUDE: Double = 0.0
    const val BLE_PIN: Long = 0
    const val CLIENT_REPEAT: Boolean = false
    const val PATH_HASH_MODE: Int = 0
    val PRE_REPEAT_FREQUENCY: Long? = null
    val PRE_REPEAT_BANDWIDTH: Long? = null
    val PRE_REPEAT_SPREADING_FACTOR: Int? = null
    val PRE_REPEAT_CODING_RATE: Int? = null
    const val MANUAL_ADD_CONTACTS: Boolean = false
    const val AUTO_ADD_CONFIG: Int = 0
    const val AUTO_ADD_MAX_HOPS: Int = 0
    const val MULTI_ACKS: Int = 2
    const val TELEMETRY_MODE_BASE: Int = 2
    const val TELEMETRY_MODE_LOC: Int = 0
    const val TELEMETRY_MODE_ENV: Int = 0
    const val ADVERT_LOCATION_POLICY: Int = 0
}

/**
 * Returns a copy with the radio configuration reset to [DeviceBackupDefaults], the surrogate `id`
 * re-minted, and Bluetooth connection methods removed. Applied to every device on backup export, so a
 * shared `.mc1backup` discloses neither the BLE PIN, the radio's coordinates, its RF configuration,
 * nor the exporting phone's live-connection handle. WiFi methods are deliberately kept —
 * restore-then-reconnect reads them. `publicKey` and `radioID` survive as the reconciliation and
 * partition keys; import re-mints `id` again on insert. Mirrors Swift `DeviceDTO.redactedForBackup()`.
 */
fun DeviceDto.redactedForBackup(): DeviceDto = copy(
    id = newLocalId(),
    frequency = DeviceBackupDefaults.FREQUENCY,
    bandwidth = DeviceBackupDefaults.BANDWIDTH,
    spreadingFactor = DeviceBackupDefaults.SPREADING_FACTOR,
    codingRate = DeviceBackupDefaults.CODING_RATE,
    txPower = DeviceBackupDefaults.TX_POWER,
    maxTxPower = DeviceBackupDefaults.MAX_TX_POWER,
    latitude = DeviceBackupDefaults.LATITUDE,
    longitude = DeviceBackupDefaults.LONGITUDE,
    blePin = DeviceBackupDefaults.BLE_PIN,
    clientRepeat = DeviceBackupDefaults.CLIENT_REPEAT,
    pathHashMode = DeviceBackupDefaults.PATH_HASH_MODE,
    preRepeatFrequency = DeviceBackupDefaults.PRE_REPEAT_FREQUENCY,
    preRepeatBandwidth = DeviceBackupDefaults.PRE_REPEAT_BANDWIDTH,
    preRepeatSpreadingFactor = DeviceBackupDefaults.PRE_REPEAT_SPREADING_FACTOR,
    preRepeatCodingRate = DeviceBackupDefaults.PRE_REPEAT_CODING_RATE,
    manualAddContacts = DeviceBackupDefaults.MANUAL_ADD_CONTACTS,
    autoAddConfig = DeviceBackupDefaults.AUTO_ADD_CONFIG,
    autoAddMaxHops = DeviceBackupDefaults.AUTO_ADD_MAX_HOPS,
    multiAcks = DeviceBackupDefaults.MULTI_ACKS,
    telemetryModeBase = DeviceBackupDefaults.TELEMETRY_MODE_BASE,
    telemetryModeLoc = DeviceBackupDefaults.TELEMETRY_MODE_LOC,
    telemetryModeEnv = DeviceBackupDefaults.TELEMETRY_MODE_ENV,
    advertLocationPolicy = DeviceBackupDefaults.ADVERT_LOCATION_POLICY,
    connectionMethods = connectionMethodsWithoutBluetooth(),
    appliedRadioPresetID = null,
)

/**
 * Returns a copy with `isActive` cleared and Bluetooth connection methods removed. Applied to every
 * device on backup import so a restored row is never mistaken for the currently connected radio and
 * a stale peripheral handle from another phone can never be dialled. Mirrors Swift
 * `DeviceDTO.cleanedForImport()`; unlike [redactedForBackup] it leaves the radio configuration alone,
 * because the envelope is the only record of it.
 */
fun DeviceDto.cleanedForImport(): DeviceDto = copy(
    isActive = false,
    connectionMethods = connectionMethodsWithoutBluetooth(),
)

private fun DeviceDto.connectionMethodsWithoutBluetooth(): List<ConnectionMethod> =
    connectionMethods.filterNot { it is ConnectionMethod.Bluetooth }
