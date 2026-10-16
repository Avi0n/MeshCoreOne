package one.meshcore.shared.settings

import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo

/** The three telemetry-mode bytes written together in one `/new_prefs` commit. */
data class TelemetryModes(val base: Int, val location: Int, val environment: Int)

/**
 * The narrow device+persistence seam [NodeConfigService] drives, mirroring the subset of the Swift
 * `SettingsService`/`ChannelService`/`MeshCoreSession` the node-config export and import touch. Kept as its
 * own interface (rather than the broad `MeshSession`) because the settings/radio writes stay platform-only
 * below the bridge; a scripted fake stands in for the pure-JVM tests. Device and contact writes live on
 * [NodeConfigWrites].
 */
interface NodeConfigSession : NodeConfigWrites {
    /** This device's current configuration snapshot. */
    suspend fun getSelfInfo(): SelfInfo

    /**
     * Exports the device private key, or throws `MeshBridgeException(FEATURE_DISABLED)` on hardened firmware
     * that disables key export; the caller continues the export without the key in that case.
     */
    suspend fun exportPrivateKey(): ByteArray

    /** Static device capabilities (max channels/contacts). */
    suspend fun queryDevice(): DeviceCapabilities

    /** Fetches contacts modified since `since` epoch seconds; `null` for the full list. */
    suspend fun getContacts(since: Long?): List<MeshContact>

    /** Reads one channel slot; the returned [ChannelInfo.index] equals [index]. */
    suspend fun getChannel(index: Int): ChannelInfo
}
