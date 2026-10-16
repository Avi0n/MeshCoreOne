package one.meshcore.shared.ui

import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.navigation.claimCliInitialKeyboardFocus
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.tools.ui.ToolsHubInputs

/** Maps the shell graph onto the tools pages. */
internal fun toolsHubInputs(
    deps: ShellDependencies,
    isRadioReady: Boolean,
    deviceLocation: () -> LatLng?,
    networkOffline: Boolean,
    recentHopsStore: RecentHopsStore,
): ToolsHubInputs = ToolsHubInputs(
    isRadioReady = isRadioReady,
    isRadioConnected = { deps.connectionState.value.isConnected },
    deviceLocation = deviceLocation,
    runTracePath = deps.runTracePath,
    rxLogStore = deps.rxLogStore,
    radioId = deps.currentRadioId,
    toolsCliDeviceName = { deps.localNodeName.value ?: "" },
    toolsCliFetchContacts = {
        val radio = deps.currentRadioId()
        val service = deps.contactService()
        if (radio == null || service == null) {
            null
        } else {
            loadContacts(service, radio)
        }
    },
    toolsCliFetchChannels = {
        val radio = deps.currentRadioId()
        if (radio == null) {
            null
        } else {
            loadToolsCliChannels {
                deps.channelService()?.getChannels(radio)
                    ?: deps.conversationListStore()?.fetchChannels(radio)
            }
        }
    },
    toolsCliRemote = deps.toolsCliRemote,
    toolsCliLocal = deps.toolsCliLocal,
    claimCliInitialKeyboardFocus = { deps.coordinator.claimCliInitialKeyboardFocus() },
    discoveredNodeStore = deps.discoveredNodeStore,
    contactService = deps.contactService,
    fetchNoiseFloorReading = deps.fetchNoiseFloorReading,
    lineOfSightWorkspace = deps.lineOfSightWorkspace,
    noiseFloorWorkspace = deps.noiseFloorWorkspace,
    savedTracePathStore = deps.savedTracePathStore,
    elevationService = deps.elevationService,
    shareText = deps.shareText,
    sendNodeDiscover = deps.sendNodeDiscover,
    nodeDiscoveryWorkspace = deps.nodeDiscoveryWorkspace,
    tracePathWorkspace = deps.tracePathWorkspace,
    meshEvents = deps.meshEvents,
    selfPublicKey = deps.selfPublicKey,
    radioSnapshot = deps.radioSnapshot,
    recentHopsStore = recentHopsStore,
    preferences = deps.pathEditPreferences,
    mapDisplayPreferences = deps.mapDisplayPreferences,
    isOffline = networkOffline,
    locationService = deps.locationService,
    contactListInvalidations = deps.contactListInvalidations,
)
