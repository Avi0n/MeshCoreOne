package one.meshcore.shared.connection

/**
 * CDM presence is only useful for the last radio the user still wants connected. Watching every
 * association also watches incomplete pairings and can wake a GATT connect for an unselected radio.
 */
fun companionPresenceHandlesToObserve(
    associations: List<CompanionAssociation>,
    lastWantedBleHandle: String?,
    userDisconnected: Boolean,
): List<String> {
    if (userDisconnected) return emptyList()
    val wanted = lastWantedBleHandle?.trim().orEmpty()
    if (wanted.isEmpty()) return emptyList()
    return associations.mapNotNull { association ->
        association.handle.takeIf { companionHandleMatches(it, wanted) }
    }
}

/** Rejects stale presence callbacks for leftover associations and explicit disconnects. */
fun shouldStartConnectionOnRadioPresence(
    appearedHandle: String?,
    lastWantedBleHandle: String?,
    userDisconnected: Boolean,
): Boolean {
    if (userDisconnected) return false
    val appeared = appearedHandle ?: return false
    val wanted = lastWantedBleHandle ?: return false
    return companionHandleMatches(appeared, wanted)
}
