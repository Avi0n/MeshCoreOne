package one.meshcore.shared.pathedit

/**
 * Outcome of a single hex code parsed from a bulk-add entry. Ported from the iOS `HopCodeStatus`.
 */
sealed interface HopCodeStatus {
    /**
     * Valid, resolves to a node, and fits within the hop cap. Carries the prebuilt [hop] so callers
     * append without re-parsing or re-resolving.
     */
    data class WillAdd(val hop: PathHop) : HopCodeStatus

    /** Valid and resolvable, but its hash prefix is already in the path. */
    data object AlreadyInPath : HopCodeStatus

    /** Valid hex but no matching node. */
    data object NotFound : HopCodeStatus

    /** Wrong length or non-hex. */
    data object InvalidFormat : HopCodeStatus

    /** Valid and resolvable but past the hop cap. */
    data object PathFull : HopCodeStatus
}

/**
 * One parsed code from a bulk-add entry, with its status. Ported from the iOS `HopCodeClassification`.
 * [code] is the uppercased, de-duplicated code and doubles as the stable id for list rendering.
 */
data class HopCodeClassification(val code: String, val status: HopCodeStatus) {
    /** True when tapping "Add" will append this code to the path. */
    val willBeAdded: Boolean get() = status is HopCodeStatus.WillAdd
}
