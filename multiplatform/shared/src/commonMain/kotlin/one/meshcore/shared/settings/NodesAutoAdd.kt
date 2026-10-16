package one.meshcore.shared.settings

import one.meshcore.shared.contact.AutoAddConfigBits
import one.meshcore.shared.contact.AutoAddMode

/** Live device fields for Settings → Advanced → Nodes auto-add. */
data class NodesAutoAddSnapshot(
    val manualAddContacts: Boolean,
    val autoAddConfig: Int,
    val autoAddMaxHops: Int,
    val firmwareVersionString: String,
) {
    val mode: AutoAddMode
        get() = AutoAddMode.mode(manualAddContacts = manualAddContacts, autoAddConfig = autoAddConfig)

    val supportsAutoAddConfig: Boolean
        get() = supportsAutoAddConfig(firmwareVersionString)

    val supportsAutoAddMaxHops: Boolean
        get() = FirmwareVersion.isAtLeast(
            firmwareVersionString,
            major = AUTO_ADD_MAX_HOPS_MAJOR,
            minor = AUTO_ADD_MAX_HOPS_MINOR,
        )

    val autoAddContacts: Boolean
        get() = autoAddConfig and AutoAddConfigBits.CONTACTS != 0

    val autoAddRepeaters: Boolean
        get() = autoAddConfig and AutoAddConfigBits.REPEATERS != 0

    val autoAddRoomServers: Boolean
        get() = autoAddConfig and AutoAddConfigBits.ROOM_SERVERS != 0

    val overwriteOldest: Boolean
        get() = autoAddConfig and AutoAddConfigBits.OVERWRITE_OLDEST != 0

    companion object {
        private const val AUTO_ADD_CONFIG_MAJOR = 1
        private const val AUTO_ADD_CONFIG_MINOR = 12
        private const val AUTO_ADD_MAX_HOPS_MAJOR = 1
        private const val AUTO_ADD_MAX_HOPS_MINOR = 14

        /**
         * Whether [firmwareVersionString] answers the auto-add-config commands at all. The connect-path
         * read and the Apply-path write share this one gate, so a radio that is never asked is also
         * never written.
         */
        fun supportsAutoAddConfig(firmwareVersionString: String): Boolean = FirmwareVersion.isAtLeast(
            firmwareVersionString,
            major = AUTO_ADD_CONFIG_MAJOR,
            minor = AUTO_ADD_CONFIG_MINOR,
        )
    }
}

/** User draft for applying auto-add policy to the radio. */
data class NodesAutoAddDraft(
    val mode: AutoAddMode,
    val autoAddContacts: Boolean = false,
    val autoAddRepeaters: Boolean = false,
    val autoAddRoomServers: Boolean = false,
    val overwriteOldest: Boolean = false,
    val autoAddMaxHops: Int = 0,
)

/**
 * Builds the wire autoAddConfig bitmask for v1.12+ from a draft. MANUAL and ALL leave type bits
 * clear (firmware uses manualAddContacts for the all/manual split). Mirrors iOS NodesSettingsSection.
 */
fun NodesAutoAddDraft.toAutoAddConfigBitmask(): Int {
    var config = 0
    if (overwriteOldest) config = config or AutoAddConfigBits.OVERWRITE_OLDEST
    if (mode == AutoAddMode.SELECTED_TYPES) {
        if (autoAddContacts) config = config or AutoAddConfigBits.CONTACTS
        if (autoAddRepeaters) config = config or AutoAddConfigBits.REPEATERS
        if (autoAddRoomServers) config = config or AutoAddConfigBits.ROOM_SERVERS
    }
    return config
}

/** Protocol: manualAddContacts is true for MANUAL and SELECTED_TYPES, false only for ALL. */
fun NodesAutoAddDraft.manualAddContactsForWire(): Boolean = mode != AutoAddMode.ALL

/** Whether [draft] differs from this snapshot, matching iOS `NodesSettingsSection.settingsModified`. */
fun NodesAutoAddSnapshot.settingsModified(draft: NodesAutoAddDraft): Boolean = draft != toDraft()

fun NodesAutoAddSnapshot.toDraft(): NodesAutoAddDraft = if (supportsAutoAddConfig) {
    NodesAutoAddDraft(
        mode = mode,
        autoAddContacts = autoAddContacts,
        autoAddRepeaters = autoAddRepeaters,
        autoAddRoomServers = autoAddRoomServers,
        overwriteOldest = overwriteOldest,
        autoAddMaxHops = autoAddMaxHops,
    )
} else {
    NodesAutoAddDraft(
        mode = if (manualAddContacts) AutoAddMode.MANUAL else AutoAddMode.ALL,
    )
}
