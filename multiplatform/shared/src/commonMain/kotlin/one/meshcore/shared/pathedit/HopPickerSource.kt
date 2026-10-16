package one.meshcore.shared.pathedit

import kotlinx.coroutines.flow.StateFlow

/**
 * Data source for the shared Add-Hop picker, ported from the iOS `HopPickerSource` protocol. Lets the
 * same picker drive both the contact path editor ([PathManagementViewModel], hop-capped) and the trace path
 * builder (uncapped) without depending on either concretely.
 *
 * The available nodes, recents, and editable-path count are read from [state] so the picker recomposes as
 * they change; the mutating actions are plain methods.
 */
interface HopPickerSource {
    /** The observable editor state the picker renders (available nodes, recents, current path). */
    val state: StateFlow<PathEditState>

    /** Maximum hops the path can hold, or `null` when unlimited (trace). The contact editor is capped. */
    val hopLimit: Int?

    /** Append a single node to the path and record it as recent. */
    fun appendHop(node: PickerNode)

    /** Add every resolvable code from a comma-separated bulk entry, honoring the hop cap. */
    fun addCodes(input: String): CodeInputResult

    /** Classify a comma-separated bulk entry per code without mutating the path, for the preview panel. */
    fun classifyCodes(input: String): List<HopCodeClassification>

    /** Hops currently in the path being built. */
    fun currentHopCount(): Int = state.value.editablePath.size

    /** Whether no further hops can be added — a path with no [hopLimit] is never full. */
    fun isPathFull(): Boolean = hopLimit?.let { currentHopCount() >= it } ?: false

    /**
     * Adopt a hash size inferred from a bulk paste when the source supports a per-entry override (trace).
     * Fixed-width sources (the contact editor) take no action.
     */
    fun adoptHashSize(pastedCodes: String) {
        // Fixed hash width: nothing to adopt.
    }
}
