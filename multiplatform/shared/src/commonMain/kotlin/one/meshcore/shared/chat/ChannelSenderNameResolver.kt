package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver

/**
 * The contact-derived tables a channel sender resolution reads: the contact book itself, for the
 * public-key-prefix match, and a `loweredName -> nickname` lookup for the name match. Mirrors the Swift
 * `ChatSenderTables`.
 */
data class ChannelSenderTables(
    val contacts: List<ContactDto> = emptyList(),
    val nicknamesByLoweredName: Map<String, String> = emptyMap(),
    val incomingAvatars: Map<String, IncomingAvatarIdentity> = emptyMap(),
) {
    companion object {
        val EMPTY = ChannelSenderTables()

        /**
         * Builds the tables for [contacts]. Only a name owned by exactly one contact contributes a
         * nickname, so an ambiguous name collision never asserts a specific identity. The key is a plain
         * `lowercase()` fold, which deliberately differs from [one.meshcore.shared.chat.SenderContactMatcher]'s
         * locale-aware compare; the O(1) per-message lookup is worth the disagreement on locale-tailored
         * case folding. Mirrors `MessageBubbleConfiguration.buildNicknameLookup`.
         */
        fun from(contacts: List<ContactDto>): ChannelSenderTables {
            val counts = contacts.groupingBy { it.name.lowercase() }.eachCount()
            val lookup = buildMap {
                contacts.forEach { contact ->
                    val key = contact.name.lowercase()
                    val nickname = contact.nickname
                    if (counts[key] == 1 && !nickname.isNullOrEmpty()) put(key, nickname)
                }
            }
            return ChannelSenderTables(
                contacts = contacts,
                nicknamesByLoweredName = lookup,
                incomingAvatars = IncomingAvatarIdentity.identitiesFrom(contacts),
            )
        }
    }
}

/**
 * Resolves the display name of a channel message's sender. A parsed wire name wins and carries the
 * matching contact's nickname as an *unverified* one (channel senders are matched by name alone, never
 * cryptographically); with no wire name the sender's public-key prefix is matched against the contact
 * book, then falls back to the prefix's leading bytes as hex, then to the localized unknown label.
 * Port of the Swift `MessageBubbleConfiguration.resolveSenderName`.
 */
object ChannelSenderNameResolver {
    /** Leading bytes of the sender key prefix rendered as the hex fallback name (Swift `prefix(2)`). */
    private const val HEX_FALLBACK_PREFIX_BYTES = 2

    fun resolve(message: MessageDto, tables: ChannelSenderTables, unknownLabel: String): NodeNameResolution =
        namedSender(message.senderNodeName, tables) ?: prefixResolution(message, tables, unknownLabel)

    private fun namedSender(senderName: String?, tables: ChannelSenderTables): NodeNameResolution? {
        if (senderName.isNullOrEmpty()) return null
        return NodeNameResolution(
            displayName = senderName,
            matchKind = NodeNameMatchKind.EXACT,
            unverifiedNickname = tables.nicknamesByLoweredName[senderName.lowercase()],
        )
    }

    // NeighborPrefixResolver signals "no contact carries this prefix" as UNRESOLVED; every matched
    // branch is EXACT or FALLBACK, so that is the safe test for falling through to the hex name.
    private fun prefixResolution(
        message: MessageDto,
        tables: ChannelSenderTables,
        unknownLabel: String,
    ): NodeNameResolution {
        val prefix = message.senderKeyPrefix ?: return unresolved(unknownLabel)
        val byPrefix = NeighborPrefixResolver.resolve(prefix, tables.contacts)
        if (byPrefix.matchKind != NodeNameMatchKind.UNRESOLVED) return byPrefix
        return hexOrUnknown(prefix, unknownLabel)
    }

    private fun hexOrUnknown(prefix: ByteArray, unknownLabel: String): NodeNameResolution {
        if (prefix.size >= HEX_FALLBACK_PREFIX_BYTES) {
            return NodeNameResolution(
                displayName = prefix.copyOf(HEX_FALLBACK_PREFIX_BYTES).toUppercaseHex(),
                matchKind = NodeNameMatchKind.UNRESOLVED,
            )
        }
        return unresolved(unknownLabel)
    }

    private fun unresolved(label: String): NodeNameResolution =
        NodeNameResolution(displayName = label, matchKind = NodeNameMatchKind.UNRESOLVED)
}

/**
 * The thread's sender resolver: a channel row resolves against the contact book, a DM row keeps its own
 * wire name. Dispatch is on `channelIndex`, not on a conversation flag, so an early rebuild can never
 * mis-route a channel row. Mirrors the Swift `senderResolutionFor` dispatch; its DM arm bakes the unknown
 * sentinel, which is deliberately not copied here because a DM bubble never shows a sender line and the
 * empty name is what the path detail treats as "no parsed sender".
 *
 * [tables] and [unknownLabel] are read at each resolve so contacts and localized copy can refresh in place.
 */
fun channelAwareSenderResolver(tables: () -> ChannelSenderTables, unknownLabel: () -> String) =
    SenderNameResolver { message ->
        if (message.channelIndex != null) {
            ChannelSenderNameResolver.resolve(message, tables(), unknownLabel())
        } else {
            NodeNameResolution(message.senderNodeName.orEmpty(), NodeNameMatchKind.UNRESOLVED)
        }
    }

/** Fixed-label convenience for non-Compose owners. */
fun channelAwareSenderResolver(tables: () -> ChannelSenderTables, unknownLabel: String) =
    channelAwareSenderResolver(tables) { unknownLabel }
