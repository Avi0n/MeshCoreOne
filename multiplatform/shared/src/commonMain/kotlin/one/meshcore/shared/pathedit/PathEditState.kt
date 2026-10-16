package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto

/**
 * The observable state of the path editor, held by [PathManagementViewModel] and rendered by the sheet
 * and the Add-Hop picker. Kotlin port of the iOS `PathManagementViewModel`'s `@Observable` surface. The
 * device-derived values (`hashSize`, `maxHopCount`, `isPathFull`) are not stored here — they read the live
 * connection through the view model, mirroring the iOS provider closures.
 */
data class PathEditState(
    /** The ordered path being edited — the core mutable state. */
    val editablePath: List<PathHop> = emptyList(),
    /** All contacts for name/key resolution. */
    val allContacts: List<ContactDto> = emptyList(),
    /** Contacts of type repeater, available to add. */
    val availableRepeaters: List<ContactDto> = emptyList(),
    /** Contacts of type room, available to add. */
    val availableRooms: List<ContactDto> = emptyList(),
    /** Passively-discovered nodes, available to add and to resolve against. */
    val discoveredNodes: List<DiscoveredNode> = emptyList(),
    /** LRU of recently inserted public keys, newest first, capped at [RecentHopsStore.LIMIT]. */
    val recentPublicKeys: List<ByteArray> = emptyList(),
    /** Drives navigation to the Add-Hop picker; `null` means not shown. */
    val insertionIntent: AddHopIntent? = null,
    /** A save/reset/set is in flight (spinner + disables). */
    val isSettingPath: Boolean = false,
    /** A path discovery is in flight. */
    val isDiscovering: Boolean = false,
    /** The modal error surface. */
    val errorMessage: PathEditError? = null,
    /** The discovery outcome, when one is being shown. */
    val discoveryResult: PathDiscoveryResult? = null,
    /** Drives the discovery-result alert. */
    val showDiscoveryResult: Boolean = false,
    /** Discovery countdown display, or `null` when idle. */
    val discoverySecondsRemaining: Int? = null,
) {
    /** Discovered repeaters available to add. */
    val discoveredRepeaters: List<DiscoveredNode> get() = discoveredNodes.filter { it.isRepeater }

    /** Combined repeaters and rooms for resolution. */
    val availableNodes: List<ContactDto> get() = availableRepeaters + availableRooms
}
