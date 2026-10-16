package one.meshcore.shared.tools

import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo

/**
 * Seam between the Tools CLI terminal's local radio commands and the connected companion radio, mirroring
 * the subset of the Swift `SettingsService` the local command executor drives. Production wires
 * [ServiceToolsCliLocalBridge] over the live session; tests inject a fake. Every op is one device
 * request-response; a device/transport failure throws and the executor renders it.
 */
interface ToolsCliLocalBridge :
    ToolsCliDeviceCommands,
    ToolsCliRadioCommands

/** Reads, clock, custom variables, advert, and reboot on the connected companion radio. */
interface ToolsCliDeviceCommands {
    /** This radio's current configuration snapshot. */
    suspend fun selfInfo(): SelfInfo

    /** Static device capabilities (version, firmware build, model, path-hash mode). */
    suspend fun deviceCapabilities(): DeviceCapabilities

    /** The radio clock as epoch seconds (UTC). */
    suspend fun getTime(): Long

    /** Sets the radio clock to the current phone time, returning the epoch seconds (UTC) written. */
    suspend fun syncClock(): Long

    /** The battery level in millivolts. */
    suspend fun batteryMillivolts(): Int

    /** All custom firmware variables, keyed by name. */
    suspend fun getCustomVars(): Map<String, String>

    /** Writes a custom firmware variable; returns false when the firmware rejects the name (unknown var). */
    suspend fun setCustomVar(key: String, value: String): Boolean

    /** Broadcasts this radio's advertisement; [flood] floods the mesh, otherwise a zero-hop advert. */
    suspend fun sendAdvert(flood: Boolean)

    suspend fun reboot()
}

/** Name, position, and radio-parameter writes on the connected companion radio. */
interface ToolsCliRadioCommands {
    /** Sets the node name, returning the verified name to refresh the local session prompt. */
    suspend fun setName(name: String): String

    suspend fun setLatitude(latitude: Double)

    suspend fun setLongitude(longitude: Double)

    suspend fun setTxPower(power: Int)

    /** Retunes the radio; the units match `SelfInfo.radioFrequency` (MHz) and `SelfInfo.radioBandwidth` (kHz). */
    suspend fun setRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: Int, codingRate: Int)

    suspend fun setFrequency(frequencyMHz: Double)

    suspend fun setMultiAcks(value: Int)

    suspend fun setPathHashMode(mode: Int)
}
