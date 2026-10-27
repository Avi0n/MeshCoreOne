// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionParser.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageDTO+ReactionVisibility.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.services.reactions.ReactionParser

/**
 * WP-213's rendering adapter over the canonical WP-216 PocketMesh parser: the reaction message hash
 * used by [MessageLRUCache] keys and channel/DM classification for
 * [HiddenOutgoingReactionPredicate.SourceWireFormat] share the same source contract.
 */
internal object ReactionWireFormat {
    /** `ReactionParser.generateMessageHash`: SHA-256(utf8(text) ‖ LE32(timestamp)), 5 bytes as Crockford Base32. */
    fun generateMessageHash(text: String, timestamp: UInt): String = ReactionParser.generateMessageHash(text, timestamp)

    /** `ReactionParser.parse`: `@[sender]emoji` or `emoji@[sender]`, newline, 8-character Crockford hash. */
    fun parse(text: String): ParsedReaction? = ReactionParser.parse(text)

    /** `ReactionParser.parseDM`: `{emoji}\n{hash}` with no `@[` anywhere. Returns `(emoji, messageHash)`. */
    fun parseDM(text: String): Pair<String, String>? = ReactionParser.parseDM(text)?.let { it.emoji to it.messageHash }
}

/**
 * Port for `MessageDTO.isHiddenOutgoingReaction(isDM:)` (WP-216 owns `ReactionParser`). A sent outgoing
 * reaction renders as a badge, so the timeline hides the row; failed reactions stay visible for retry.
 * [SourceWireFormat] is the documented default and reproduces the Swift extension exactly with the
 * canonical PocketMesh parsers.
 */
fun interface HiddenOutgoingReactionPredicate {
    fun isHiddenOutgoingReaction(message: MessageDTO, isDM: Boolean): Boolean

    object SourceWireFormat : HiddenOutgoingReactionPredicate {
        override fun isHiddenOutgoingReaction(message: MessageDTO, isDM: Boolean): Boolean {
            if (message.direction != MessageDirection.OUTGOING) return false
            val isReaction = if (isDM) ReactionWireFormat.parseDM(message.text) != null else ReactionWireFormat.parse(message.text) != null
            if (!isReaction) return false
            return message.status != MessageStatus.FAILED
        }
    }
}
