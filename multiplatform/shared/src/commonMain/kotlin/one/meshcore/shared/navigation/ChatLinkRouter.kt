package one.meshcore.shared.navigation

import one.meshcore.shared.model.toUppercaseHex

/**
 * The action a `meshcore://` contact or channel link resolves to, once matched against local state.
 * The navigation coordinator performs the action; this layer only decides it.
 */
sealed interface ChatLinkRoute {
    /** The link is the connected radio's own key — adding yourself is a no-op. */
    data object Ignore : ChatLinkRoute

    /** A contact with this key already exists for the current radio; open its detail. */
    data class OpenContactDetail(val contact: MeshCoreDeepLink.Contact) : ChatLinkRoute

    /** No such contact yet; present the add-contact confirmation. */
    data class ConfirmAddContact(val contact: MeshCoreDeepLink.Contact) : ChatLinkRoute

    /** A channel with this secret already exists; open it. */
    data class OpenChannel(val channel: MeshCoreDeepLink.Channel) : ChatLinkRoute

    /** No such channel yet; present the join confirmation. */
    data class ConfirmJoinChannel(val channel: MeshCoreDeepLink.Channel) : ChatLinkRoute
}

/**
 * Pure routing rules for `meshcore://` contact and channel links, ported from the iOS `ChatLinkRouter`.
 * Identity matching is done on uppercase-hex strings so callers can index existing records without
 * exposing raw byte arrays.
 */
object ChatLinkRouter {
    /**
     * @param ownPublicKey the connected radio's own public key, or `null` when disconnected.
     * @param existingContactKeyHexes uppercase-hex public keys of contacts already stored for the
     *   current radio.
     */
    fun routeContact(
        contact: MeshCoreDeepLink.Contact,
        ownPublicKey: ByteArray?,
        existingContactKeyHexes: Set<String>,
    ): ChatLinkRoute {
        val keyHex = contact.publicKey.toUppercaseHex()
        if (ownPublicKey != null && keyHex == ownPublicKey.toUppercaseHex()) return ChatLinkRoute.Ignore
        return if (keyHex in existingContactKeyHexes) {
            ChatLinkRoute.OpenContactDetail(contact)
        } else {
            ChatLinkRoute.ConfirmAddContact(contact)
        }
    }

    /**
     * @param existingChannelSecretHexes uppercase-hex secrets of channels already stored.
     */
    fun routeChannel(channel: MeshCoreDeepLink.Channel, existingChannelSecretHexes: Set<String>): ChatLinkRoute =
        if (channel.id in existingChannelSecretHexes) {
            ChatLinkRoute.OpenChannel(channel)
        } else {
            ChatLinkRoute.ConfirmJoinChannel(channel)
        }
}
