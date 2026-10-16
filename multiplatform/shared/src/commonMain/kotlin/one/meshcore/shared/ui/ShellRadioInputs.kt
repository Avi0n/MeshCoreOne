package one.meshcore.shared.ui

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset

interface ShellRadioHost {
    /** Volatile discovered-node store for Nodes → Discover; `null` hides discovery or shows empty. */
    val discoveredNodeStore: () -> DiscoveredNodeStore?

    /**
     * Live LoRa parameters for Settings → Radio (Device-row units). `null` when disconnected or the
     * platform has not wired a snapshot source yet.
     */
    val radioSnapshot: () -> RadioDeviceSnapshot?

    /**
     * Whether a Settings screen may re-read the radio right now, gating [refreshDeviceSettings] the way
     * iOS gates its settings startup reads on `AppState.canRunSettingsStartupReads`.
     */
    val settingsStartupReadsAllowed: StateFlow<Boolean>

    /**
     * Re-reads the radio's own settings into the cache the Settings snapshots render, so a screen opened
     * long after connect shows what the radio holds now. Deliberately without a default: the snapshot
     * lambdas above all read a cache, so a container that omitted this would render connect-time values
     * forever and nothing would say so.
     */
    val refreshDeviceSettings: suspend () -> Unit

    /** Auto-add policy for Settings → Advanced → Nodes; `null` when disconnected. */
    val nodesAutoAddSnapshot: () -> NodesAutoAddSnapshot?

    /** Writes auto-add mode/types to the connected radio. */
    val applyNodesAutoAdd: suspend (NodesAutoAddDraft) -> Unit

    /**
     * User region selection for preset filtering/recommendation; `null` when unset or unread.
     */
    val regionSelection: () -> RegionSelection?

    /**
     * Persists a Settings → Radio location pick. Default no-op for desktop/tests.
     */
    val setRegionSelection: suspend (RegionSelection) -> Unit

    /**
     * Resolves a region suggestion from the device location (RegionResolver). Null on any failure
     * (unauthorized, timeout, no country); the onboarding region step then falls to manual entry.
     * Default no-op so desktop and tests compile without a location stack.
     */
    val resolveRegion: suspend () -> RegionSelection?

    /**
     * Applies a radio preset over the live session (`setRadio` in wire units). Default no-ops so desktop
     * and tests compile without a radio.
     */
    val applyRadioPreset: suspend (RadioPreset) -> Unit

    /**
     * Applies manual LoRa params from Settings → Advanced. Default no-op for desktop/tests.
     */
    val applyManualRadioParams: suspend (ManualRadioParams) -> Unit

    /**
     * Connection settings read model (BLE PIN, WiFi methods, transport); `null` when disconnected.
     */
    val connectionSettingsSnapshot: () -> ConnectionSettingsSnapshot?

    /** Writes a custom BLE pairing PIN on the live radio; null when the write path is unavailable. */
    val setBlePin: (suspend (Long) -> Unit)?

    /** Updates stored WiFi host/port for the last device; null when the write path is unavailable. */
    val updateWifiEndpoint: (suspend (String, Int) -> Unit)?

    /**
     * Local device OCV (battery-curve) editor state from the Device row; `null` when no device.
     */
    val deviceOcvSnapshot: () -> OcvEditorState?

    /**
     * Persists device OCV preset/values (app-side only). Null when the write path is unavailable.
     */
    val updateDeviceOcv: (suspend (OCVPreset, List<Int>) -> Unit)?

    /**
     * Location settings read model (coordinates + advert policy); `null` when disconnected.
     */
    val locationSettingsSnapshot: () -> LocationSettingsSnapshot?

    /**
     * Share-location-publicly toggle (setOtherParams). Default no-op for desktop/tests.
     */
    val setShareLocationPublicly: suspend (Boolean) -> Unit

    /**
     * Writes the advertisement location policy the Location screen resolves from the share toggle and the
     * GPS source. `null` falls back to [setShareLocationPublicly], which cannot express "advertise the
     * node's live fix".
     */
    val setAdvertLocationPolicy: (suspend (AdvertLocationPolicy) -> Unit)?

    /** Writes node coordinates from the location picker to the connected radio. */
    val setDeviceCoordinates: suspend (Double, Double) -> Unit

    /**
     * The connected Device row's id, which scopes the per-radio location preferences under the same key
     * strings iOS uses. `null` when no radio has been paired.
     */
    val connectedDeviceId: () -> String?

    /** Reads the radio's on-board GPS capability and state; `null` when the read path is unavailable. */
    val readDeviceGpsState: (suspend () -> DeviceGpsState)?

    /** Writes and verifies the radio's on-board GPS toggle; `null` hides the device-GPS section. */
    val setDeviceGpsEnabled: (suspend (Boolean) -> Unit)?

    /** Renames the connected radio (clamped and verified); `null` leaves the name read-only. */
    val setNodeName: (suspend (String) -> Unit)?

    /** The connected radio's key-prefix hash size for path encoding; `null` clamps to the default of 1. */
    val deviceHashSize: () -> Int?

    /** Passively-discovered nodes for path-hop resolution, keyed by radio; empty when none are known. */
    val pathEditDiscoveredNodes: suspend (Uuid) -> List<DiscoveredNode>

    /**
     * Telemetry-sharing / multi-ack / path-hash read model for Settings → Advanced; `null` when
     * disconnected.
     */
    val deviceOtherParamsSnapshot: () -> one.meshcore.shared.settings.DeviceOtherParamsSnapshot?

    /** Writes telemetry-sharing modes (base/location/environment) to the connected radio. */
    val setTelemetryModes: suspend (base: Int, location: Int, environment: Int) -> Unit

    /** Writes the direct-message acknowledgment count (wire value 0 or 1) to the connected radio. */
    val setMultiAcks: suspend (Int) -> Unit

    /** Writes the routing key-prefix hash size (firmware v10+) to the connected radio. */
    val setPathHashMode: suspend (Int) -> Unit

    /** Device-row known regions for Settings → Advanced → Default Flood Scope / Manage Regions. */
    val knownRegions: () -> List<String>

    /** The device's persisted default flood-scope name; `null` means disabled. */
    val defaultFloodScopeName: () -> String?

    /** Writes and verifies the device's default flood scope (`null` clears it). */
    val applyDefaultFloodScope: suspend (String?) -> Unit

    /** Appends a manually-entered region to Device.knownRegions. */
    val addKnownRegion: suspend (String) -> Unit

    /** Removes a region from Device.knownRegions, clearing the default scope if it matched. */
    val removeKnownRegion: suspend (String) -> Unit

    /** Device-level nearby-region discovery, shared with the per-channel flood-scope picker. */
    val discoverNearbyRegions: suspend () -> one.meshcore.shared.channel.RegionDiscoverUiResult

    /** Localized status copy for a [discoverNearbyRegions] result. */
    val regionDiscoverMessage: suspend (one.meshcore.shared.channel.RegionDiscoverUiResult) -> String?

    /** Reboots the connected companion device (Settings → Advanced). Null hides the action. */
    val rebootDevice: (suspend () -> Unit)?

    /** Enters client-repeat mode at the nearest repeat preset, saving pre-repeat parameters. */
    val enableClientRepeat: suspend () -> Unit

    /** Leaves repeat mode, restoring saved pre-repeat parameters or the radio's current params. */
    val disableClientRepeat: suspend () -> Unit

    /**
     * Retunes to a chosen repeat-preset frequency while already in repeat mode (frequency only, pre-repeat
     * parameters untouched). Lets the user pick which repeat frequency after enabling repeat mode.
     */
    val applyRepeatPreset: suspend (RadioPreset) -> Unit
}

/** Radio, region, location, and repeat-mode settings the shell writes. */
data class ShellRadioInputs(
    override val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    override val radioSnapshot: () -> RadioDeviceSnapshot? = { null },
    override val settingsStartupReadsAllowed: StateFlow<Boolean>,
    override val refreshDeviceSettings: suspend () -> Unit,
    override val nodesAutoAddSnapshot: () -> NodesAutoAddSnapshot? = { null },
    override val applyNodesAutoAdd: suspend (NodesAutoAddDraft) -> Unit = {},
    override val regionSelection: () -> RegionSelection? = { null },
    override val setRegionSelection: suspend (RegionSelection) -> Unit = {},
    override val resolveRegion: suspend () -> RegionSelection? = { null },
    override val applyRadioPreset: suspend (RadioPreset) -> Unit = {},
    override val applyManualRadioParams: suspend (ManualRadioParams) -> Unit = {},
    override val connectionSettingsSnapshot: () -> ConnectionSettingsSnapshot? = { null },
    override val setBlePin: (suspend (Long) -> Unit)? = null,
    override val updateWifiEndpoint: (suspend (String, Int) -> Unit)? = null,
    override val deviceOcvSnapshot: () -> OcvEditorState? = { null },
    override val updateDeviceOcv: (suspend (OCVPreset, List<Int>) -> Unit)? = null,
    override val locationSettingsSnapshot: () -> LocationSettingsSnapshot? = { null },
    override val setShareLocationPublicly: suspend (Boolean) -> Unit = {},
    override val setAdvertLocationPolicy: (suspend (AdvertLocationPolicy) -> Unit)? = null,
    override val setDeviceCoordinates: suspend (Double, Double) -> Unit = { _, _ -> },
    override val connectedDeviceId: () -> String? = { null },
    override val readDeviceGpsState: (suspend () -> DeviceGpsState)? = null,
    override val setDeviceGpsEnabled: (suspend (Boolean) -> Unit)? = null,
    override val setNodeName: (suspend (String) -> Unit)? = null,
    override val deviceHashSize: () -> Int? = { null },
    override val pathEditDiscoveredNodes: suspend (Uuid) -> List<DiscoveredNode> = { emptyList() },
    override val deviceOtherParamsSnapshot: () -> one.meshcore.shared.settings.DeviceOtherParamsSnapshot? = { null },
    override val setTelemetryModes: suspend (base: Int, location: Int, environment: Int) -> Unit = { _, _, _ -> },
    override val setMultiAcks: suspend (Int) -> Unit = {},
    override val setPathHashMode: suspend (Int) -> Unit = {},
    override val knownRegions: () -> List<String> = { emptyList() },
    override val defaultFloodScopeName: () -> String? = { null },
    override val applyDefaultFloodScope: suspend (String?) -> Unit = {},
    override val addKnownRegion: suspend (String) -> Unit = {},
    override val removeKnownRegion: suspend (String) -> Unit = {},
    override val discoverNearbyRegions: suspend () -> one.meshcore.shared.channel.RegionDiscoverUiResult =
        {
            one.meshcore.shared.channel.RegionDiscoverUiResult.SendFailed
        },
    override val regionDiscoverMessage: suspend (one.meshcore.shared.channel.RegionDiscoverUiResult) -> String? =
        { null },
    override val rebootDevice: (suspend () -> Unit)? = null,
    override val enableClientRepeat: suspend () -> Unit = {},
    override val disableClientRepeat: suspend () -> Unit = {},
    override val applyRepeatPreset: suspend (RadioPreset) -> Unit = {},
) : ShellRadioHost
