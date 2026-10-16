package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The channel sender-name resolution the bubble bakes: a parsed wire name carries the matching contact's
 * unverified nickname, a nameless message resolves through its public-key prefix, and the hex / unknown
 * fallbacks keep the sender line from rendering blank. Mirrors the Swift
 * `MessageBubbleConfiguration.resolveSenderName` / `buildNicknameLookup`.
 */
class ChannelSenderNameResolverTest {
    private val unknown = "Unknown"

    private fun contact(name: String, nickname: String? = null, publicKey: ByteArray = ByteArray(0)) = ContactDto(
        id = ChatFixtures.freshUuid(),
        radioID = ChatFixtures.RADIO,
        publicKey = publicKey,
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
        nickname = nickname,
    )

    private fun channelMessage(senderNodeName: String? = null, senderKeyPrefix: ByteArray? = null) =
        ChatFixtures.message(channelIndex = 1, senderNodeName = senderNodeName)
            .copy(senderKeyPrefix = senderKeyPrefix)

    @Test
    fun parsedSenderNameCarriesTheMatchingContactNickname() {
        val tables = ChannelSenderTables.from(listOf(contact("Alpha", nickname = "Rico")))

        val resolution = ChannelSenderNameResolver.resolve(channelMessage(senderNodeName = "alpha"), tables, unknown)

        assertEquals("alpha", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
        assertEquals("Rico", resolution.unverifiedNickname)
    }

    @Test
    fun ambiguousNameNeverAssertsANickname() {
        val tables = ChannelSenderTables.from(
            listOf(contact("Alpha", nickname = "Rico"), contact("alpha", nickname = "Sam")),
        )

        val resolution = ChannelSenderNameResolver.resolve(channelMessage(senderNodeName = "Alpha"), tables, unknown)

        assertNull(resolution.unverifiedNickname)
    }

    @Test
    fun keyPrefixResolvesToTheMatchingContact() {
        val prefix = byteArrayOf(0x0A, 0x0B, 0x0C)
        val tables = ChannelSenderTables.from(listOf(contact("Bravo", publicKey = prefix + byteArrayOf(0x01))))

        val resolution = ChannelSenderNameResolver.resolve(channelMessage(senderKeyPrefix = prefix), tables, unknown)

        assertEquals("Bravo", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun unmatchedKeyPrefixFallsBackToTheLeadingTwoBytesAsHex() {
        val resolution = ChannelSenderNameResolver.resolve(
            channelMessage(senderKeyPrefix = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())),
            ChannelSenderTables.EMPTY,
            unknown,
        )

        assertEquals("ABCD", resolution.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, resolution.matchKind)
    }

    @Test
    fun messageWithNoNameAndNoPrefixResolvesToTheUnknownLabel() {
        val resolution = ChannelSenderNameResolver.resolve(channelMessage(), ChannelSenderTables.EMPTY, unknown)

        assertEquals(unknown, resolution.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, resolution.matchKind)
    }

    @Test
    fun singleByteKeyPrefixResolvesToTheUnknownLabelRatherThanAHalfByteHex() {
        val resolution = ChannelSenderNameResolver.resolve(
            channelMessage(senderKeyPrefix = byteArrayOf(0xAB.toByte())),
            ChannelSenderTables.EMPTY,
            unknown,
        )

        assertEquals(unknown, resolution.displayName)
    }

    @Test
    fun channelRowsResolveThroughTheContactBookAndDmRowsDoNot() {
        var tables = ChannelSenderTables.EMPTY
        val resolver = channelAwareSenderResolver(tables = { tables }, unknownLabel = unknown)
        tables = ChannelSenderTables.from(listOf(contact("Alpha", nickname = "Rico")))

        val channel = resolver.resolve(channelMessage(senderNodeName = "Alpha"))
        val dm = resolver.resolve(ChatFixtures.message(senderNodeName = "Alpha"))

        assertEquals("Rico", channel.unverifiedNickname)
        assertNull(dm.unverifiedNickname)
    }

    @Test
    fun channelRowsUseTheCurrentUnknownLabel() {
        var currentUnknown = "Unknown"
        val resolver = channelAwareSenderResolver(
            tables = { ChannelSenderTables.EMPTY },
            unknownLabel = { currentUnknown },
        )
        val message = channelMessage(senderNodeName = "")

        assertEquals("Unknown", resolver.resolve(message).displayName)
        currentUnknown = "Desconocido"
        assertEquals("Desconocido", resolver.resolve(message).displayName)
    }
}
