package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity

/**
 * A freshly minted `radioID`. The default identity resolver for a first-ever pairing; a reconnect
 * reuses the existing row's `radioID` instead (see `resolveRadioId`), so the partition key is minted
 * exactly once per identity and never derived from the volatile transport handle.
 */
internal fun randomRadioId(): Uuid = Uuid.random()

/**
 * The row a first-ever pairing starts from: identity, and every other column at the value the connect
 * path is about to overwrite or the entity's own default. The connect path copies the radio's report
 * onto this row (or onto the stored one), so this is the single place a newly added column has to be
 * named — and a column with no default fails to compile here rather than blanking on every link-up.
 */
internal fun firstPairDeviceRow(rowId: Uuid, radioId: Uuid, publicKey: ByteArray): DeviceEntity = DeviceEntity(
    id = rowId,
    radioID = radioId,
    publicKey = publicKey,
    nodeName = "",
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
)

/**
 * Drops saved pre-repeat parameters the radio can no longer be restored to: they describe the settings
 * repeat mode replaced, so a radio that reports itself out of repeat mode has already been restored (or
 * was taken out of repeat mode elsewhere) and the saved values now point at nothing.
 */
internal fun DeviceEntity.withoutOrphanedPreRepeatParams(clientRepeat: Boolean): DeviceEntity =
    if (clientRepeat || preRepeatFrequency == null) {
        this
    } else {
        copy(
            preRepeatFrequency = null,
            preRepeatBandwidth = null,
            preRepeatSpreadingFactor = null,
            preRepeatCodingRate = null,
        )
    }
