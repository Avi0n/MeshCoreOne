package one.meshcore.shared.settings

import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid

/**
 * Device and local writes [NodeConfigSession] applies during a config import. [setChannel] and [setContact]
 * fold the device write and the local upsert into one op, and [setContact] swallows a local-save failure
 * after the device add lands (so only a device failure propagates), matching the Swift writer closures.
 */
interface NodeConfigWrites {
    /** Restores a device identity from a 32-byte private key. */
    suspend fun importPrivateKey(privateKey: ByteArray)

    /** Sets the node name. */
    suspend fun setNodeName(name: String)

    /** Sets the advertised position. */
    suspend fun setLocation(latitude: Double, longitude: Double)

    /**
     * Commits the merged other-parameters blob. [advertLocationPolicyRaw] is a raw byte so a policy the app
     * does not model is sent verbatim rather than coerced.
     */
    suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicyRaw: Int,
        multiAcks: Int,
    )

    /** Retunes the radio. [bandwidthKHz] carries Hz (a firmware-parameter misnomer); pass the plan value directly. */
    suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte)

    /** Sets the transmit power in dBm. */
    suspend fun setTxPower(txPower: Byte)

    /** Writes a channel slot (name + 16-byte secret) to the device and persists it locally. */
    suspend fun setChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray)

    /** Adds a contact to the device and upserts it locally, swallowing a local-save failure after the add. */
    suspend fun setContact(radioID: Uuid, contact: MeshContact)
}
