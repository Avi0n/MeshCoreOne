package one.meshcore.shared.chat.text

import one.meshcore.shared.backup.ContactDto

/** What a mention tap resolves to: open a single matched contact, or present a disambiguation picker. */
sealed interface MentionTapResult {
    /** Exactly one contact matched the mention name; open its detail. */
    data class NavigateToContact(val contact: ContactDto) : MentionTapResult

    /**
     * The tap needs disambiguation: [matches] is empty for an unknown name, holds the candidates for an
     * ambiguous one, and [isSelfMention] is true when the name is the connected radio's own node name.
     * [name] is the sanitized display form the picker title uses.
     */
    data class Picker(val name: String, val matches: List<ContactDto>, val isSelfMention: Boolean) : MentionTapResult
}

/**
 * Pure resolution for a mention tap, ported from the iOS `MentionTapEvaluator`. The name is sanitized and
 * trimmed first (a tapped name is attacker-controlled). An empty name or an ambiguous one routes to the
 * picker; the local node name routes to the self picker; exactly one contact match navigates directly.
 */
object MentionTapEvaluator {
    fun evaluate(name: String, contacts: List<ContactDto>, connectedDeviceName: String?): MentionTapResult {
        val clean = MentionUtilities.sanitizeDisplayName(name).trim()
        val isSelf = clean.isNotEmpty() &&
            connectedDeviceName != null &&
            clean.equals(connectedDeviceName, ignoreCase = true)
        val matches = when {
            clean.isEmpty() || isSelf -> emptyList()
            else -> contacts.filter { it.name.equals(clean, ignoreCase = true) }
        }
        return when {
            matches.size == 1 -> MentionTapResult.NavigateToContact(matches.first())
            else -> MentionTapResult.Picker(name = clean, matches = matches, isSelfMention = isSelf)
        }
    }
}
