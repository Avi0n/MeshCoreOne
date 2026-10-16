package one.meshcore.shared.tools.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.tools.ToolsCliLocalBridge
import one.meshcore.shared.tools.ToolsCliRemoteBridge
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.trace.SavedTracePathStore

/** Values the selected tool reads. The tools list only needs [isRadioReady]. */
internal data class ToolsHubInputs(
    val isRadioReady: Boolean,
    /** Live radio name for CLI prompts; the provider follows connection-graph replacements. */
    val toolsCliDeviceName: () -> String,
    /**
     * This device's position for the trace's start and end hops (iOS `bestAvailableLocation`). Required:
     * a hub mounted without it silently understates every trace distance and drops the device pins.
     */
    val deviceLocation: () -> LatLng?,
    val isRadioConnected: () -> Boolean = { false },
    val runTracePath: TracePathRunner? = null,
    val rxLogStore: RxLogStore? = null,
    val radioId: () -> Uuid? = { null },
    val toolsCliFetchContacts: (suspend () -> List<ContactDto>?)? = null,
    val toolsCliFetchChannels: (suspend () -> List<ChannelDto>?)? = null,
    val toolsCliRemote: (() -> ToolsCliRemoteBridge?)? = null,
    /** Tools → CLI local radio commands (clock/ver/board/advert/get/set/…); null when disconnected. */
    val toolsCliLocal: (() -> ToolsCliLocalBridge?)? = null,
    val claimCliInitialKeyboardFocus: () -> Boolean = { true },
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    val contactService: () -> ContactService? = { null },
    val fetchNoiseFloorReading: (suspend () -> NoiseFloorReading?)? = null,
    val lineOfSightWorkspace: LineOfSightViewModel? = null,
    val noiseFloorWorkspace: NoiseFloorViewModel? = null,
    /** Process-lifetime Room store when provided; otherwise the hub uses an in-memory fallback. */
    val savedTracePathStore: SavedTracePathStore? = null,
    val elevationService: ElevationService? = null,
    val shareText: (String) -> Unit = {},
    /** Active node discovery (Tools); null disables the scan button path. */
    val sendNodeDiscover: NodeDiscoverRunner? = null,
    val nodeDiscoveryWorkspace: NodeDiscoveryWorkspace? = null,
    val tracePathWorkspace: TracePathWorkspace? = null,
    val meshEvents: () -> Flow<MeshEvent>? = { null },
    /** Local radio public key for RX Log "You" hop / DM from-to resolution. */
    val selfPublicKey: () -> ByteArray? = { null },
    /** Live LoRa/firmware fields for the connected radio; gates the trace hash-size override picker. */
    val radioSnapshot: () -> RadioDeviceSnapshot? = { null },
    /** Per-radio recent-hop LRU shared with the contact path editor; `null` empties the picker's Recent section. */
    val recentHopsStore: RecentHopsStore? = null,
    val preferences: PreferencesStore? = null,
    val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    val isOffline: Boolean = false,
    val locationService: LocationService? = null,
    val contactListInvalidations: Flow<Unit> = emptyFlow(),
)
