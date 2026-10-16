package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid

/**
 * A Companion Device Manager association — Android's analog of an ASK accessory.
 * [handle] is the BLE MAC / Kable peripheral id, never [SavedRadioDevice.id].
 */
data class CompanionAssociation(val handle: String, val name: String)

/** Case-insensitive MAC / Kable-id match; blank values never match. */
fun companionHandleMatches(left: String, right: String): Boolean {
    val a = left.trim()
    val b = right.trim()
    return a.isNotEmpty() && a.equals(b, ignoreCase = true)
}

/**
 * iOS `systemAccessoriesMissingDeviceRecord`: associations with no saved Device row,
 * omitting the live connection and in-flight attempt.
 */
fun leftoverCompanionAssociations(
    associations: List<CompanionAssociation>,
    savedBleHandles: Collection<String>,
    protectedHandles: Collection<String> = emptyList(),
): List<CompanionAssociation> = associations.filter { association ->
    val handle = association.handle.trim()
    handle.isNotEmpty() &&
        savedBleHandles.none { companionHandleMatches(handle, it) } &&
        protectedHandles.none { companionHandleMatches(handle, it) }
}

/**
 * Pairing leftover rows from Device records plus the live link. Passing no saved
 * handles would treat a paired radio as stranded Set Up.
 */
fun leftoverCompanionAssociationsForPairing(
    associations: List<CompanionAssociation>,
    savedDevices: List<SavedRadioDevice>,
    connectionState: DeviceConnectionState,
    lastConnectedId: Uuid?,
): List<CompanionAssociation> = leftoverCompanionAssociations(
    associations = associations,
    savedBleHandles = savedDevices.mapNotNull { it.bleHandle },
    protectedHandles = leftoverProtectedHandles(savedDevices, connectionState, lastConnectedId),
)

/**
 * Handles that must not appear as leftover "Set Up" rows: the radio this app is
 * currently linking, matched by last-connected id. Disconnected sessions protect nothing.
 */
fun leftoverProtectedHandles(
    savedDevices: List<SavedRadioDevice>,
    connectionState: DeviceConnectionState,
    lastConnectedId: Uuid?,
): List<String> {
    if (connectionState == DeviceConnectionState.DISCONNECTED) return emptyList()
    return savedDevices.filter { it.id == lastConnectedId }.mapNotNull { it.bleHandle }
}

/**
 * iOS `clearStaleRegistrations`: drop every Companion Device Manager association so a
 * factory-reset radio can appear in the system chooser again.
 */
fun clearStaleCompanionPairings(associations: List<CompanionAssociation>, disassociate: (String) -> Unit) {
    associations.forEach { association ->
        val handle = association.handle.trim()
        if (handle.isNotEmpty()) disassociate(handle)
    }
}

/** iOS disables Clear Previous Pairing when `pairedAccessoriesCount == 0`. */
fun troubleshootingFactoryResetEnabled(pairingCount: Int): Boolean = pairingCount > 0

/**
 * Associations present in [previous] but gone from [current]. Companion Device Manager
 * `OnAssociationsChangedListener` (and a foreground snapshot on older APIs) feeds this so
 * Settings unpair can demote the Device row the way ASK `accessoryRemoved` does.
 */
fun companionAssociationRemovals(
    previous: List<CompanionAssociation>,
    current: List<CompanionAssociation>,
): List<CompanionAssociation> = previous.filter { prior ->
    current.none { companionHandleMatches(prior.handle, it.handle) }
}

/**
 * Companion Device Manager `removeBond` is public from API 36. Forget/disassociate
 * drops the OS Bluetooth pairing first so Remove and Try Again can re-prompt a PIN.
 */
internal const val COMPANION_REMOVE_BOND_MIN_SDK = 36

fun companionDeviceManagerCanRemoveBond(sdkInt: Int): Boolean = sdkInt >= COMPANION_REMOVE_BOND_MIN_SDK

/** Companion Device Manager device-presence observation is public from API 31. */
internal const val COMPANION_PRESENCE_MIN_SDK = 31

fun companionDeviceManagerCanObservePresence(sdkInt: Int): Boolean = sdkInt >= COMPANION_PRESENCE_MIN_SDK

/** Saved picker row whose BLE handle matches a Companion Device Manager MAC. */
fun savedDeviceMatchingCompanionHandle(devices: List<SavedRadioDevice>, handle: String): SavedRadioDevice? =
    devices.firstOrNull { device ->
        device.bleHandle?.let { companionHandleMatches(handle, it) } == true
    }
