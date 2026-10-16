package one.meshcore.shared.tools.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.trace.RepeaterPathInfo
import one.meshcore.shared.trace.RepeaterTapOutcome
import one.meshcore.shared.trace.TracePathHopSelection

/** One repeater-pin tap on the trace map, plus the edits that pin is allowed to make. */
internal data class TracePathMapTap(
    val id: String,
    val isRunning: Boolean,
    val contacts: List<ContactDto>,
    val discovered: List<DiscoveredNode>,
    val pathState: Map<Uuid, RepeaterPathInfo>,
    val onAppendContact: (ContactDto) -> Unit,
    val onAppendDiscovered: (DiscoveredNode) -> Unit,
    val onRemoveLast: () -> Unit,
    val onRejected: () -> Unit,
    val onApplied: () -> Unit,
)

/**
 * Applies a map pin tap. A running trace ignores the tap. A pin that cannot be selected still
 * answers, otherwise the tap reads as a dropped input.
 */
internal fun applyTracePathMapTap(tap: TracePathMapTap) {
    if (tap.isRunning) return
    val contact = tap.contacts.firstOrNull { it.id.value == tap.id }
    val discovered = tap.discovered.firstOrNull { it.id.value == tap.id }
    val tapId = contact?.id ?: discovered?.id ?: return
    val outcome = TracePathHopSelection.handleTap(tapId, tap.pathState)
    when (outcome) {
        RepeaterTapOutcome.ADDED -> {
            if (contact != null) {
                tap.onAppendContact(contact)
            } else if (discovered != null) {
                tap.onAppendDiscovered(discovered)
            }
        }
        RepeaterTapOutcome.REMOVED -> tap.onRemoveLast()
        RepeaterTapOutcome.REJECTED_MIDDLE_HOP -> tap.onRejected()
    }
    if (outcome != RepeaterTapOutcome.REJECTED_MIDDLE_HOP) tap.onApplied()
}
