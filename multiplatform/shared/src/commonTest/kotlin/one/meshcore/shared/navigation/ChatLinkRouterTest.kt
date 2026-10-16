package one.meshcore.shared.navigation

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Mirrors the iOS `ChatLinkRouter` contact/channel routing rules. */
class ChatLinkRouterTest {
    private val ownKey = ByteArray(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES) { 0x11 }
    private val otherKey = ByteArray(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES) { 0x22 }

    private fun contact(key: ByteArray) =
        MeshCoreDeepLink.Contact(name = "Node", publicKey = key, type = ContactType.CHAT)

    private fun channel(secretByte: Int) = MeshCoreDeepLink.Channel(
        name = "Base",
        secret = ByteArray(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES) { secretByte.toByte() },
    )

    @Test
    fun aContactLinkForTheConnectedRadiosOwnKeyIsIgnored() {
        val route = ChatLinkRouter.routeContact(
            contact(ownKey),
            ownPublicKey = ownKey,
            existingContactKeyHexes = emptySet(),
        )
        assertEquals(ChatLinkRoute.Ignore, route)
    }

    @Test
    fun anExistingContactOpensItsDetail() {
        val existing = contact(otherKey)
        val route = ChatLinkRouter.routeContact(
            existing,
            ownPublicKey = ownKey,
            existingContactKeyHexes = setOf(otherKey.toUppercaseHex()),
        )
        assertIs<ChatLinkRoute.OpenContactDetail>(route)
    }

    @Test
    fun anUnknownContactStagesTheAddConfirmation() {
        val route = ChatLinkRouter.routeContact(
            contact(otherKey),
            ownPublicKey = ownKey,
            existingContactKeyHexes = emptySet(),
        )
        assertIs<ChatLinkRoute.ConfirmAddContact>(route)
    }

    @Test
    fun anUnknownContactStagesConfirmationEvenWhenDisconnected() {
        val route = ChatLinkRouter.routeContact(
            contact(otherKey),
            ownPublicKey = null,
            existingContactKeyHexes = emptySet(),
        )
        assertIs<ChatLinkRoute.ConfirmAddContact>(route)
    }

    @Test
    fun anExistingChannelOpensIt() {
        val existing = channel(secretByte = 0x01)
        val route = ChatLinkRouter.routeChannel(existing, existingChannelSecretHexes = setOf(existing.id))
        assertIs<ChatLinkRoute.OpenChannel>(route)
    }

    @Test
    fun anUnknownChannelStagesTheJoinConfirmation() {
        val route = ChatLinkRouter.routeChannel(channel(secretByte = 0x01), existingChannelSecretHexes = emptySet())
        assertIs<ChatLinkRoute.ConfirmJoinChannel>(route)
    }
}
