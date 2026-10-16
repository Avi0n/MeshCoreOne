package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Point A of the message path map: the sender pin. It resolves against every contact, so an ordinary chat
 * contact — the usual sender of a DM — plots, mirroring the iOS `MessagePathMapView` sender lookup.
 */
class MessagePathSenderLocationTest {
    private val senderKey = ByteArray(PUBLIC_KEY_LENGTH) { index ->
        if (index < PREFIX_LENGTH) (index + 1).toByte() else 0
    }
    private val senderPrefix = senderKey.copyOf(PREFIX_LENGTH)

    private fun contact(
        name: String,
        key: ByteArray,
        type: ContactType,
        latitude: Double,
        longitude: Double,
    ): ContactDto = ChatFixtures.contact(name = name).copy(
        publicKey = key,
        typeRawValue = type.raw,
        latitude = latitude,
        longitude = longitude,
    )

    @Test
    fun `chat contact resolves as the sender pin`() {
        val sender = contact("Alice", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        val repeater = contact("Hilltop", ByteArray(PUBLIC_KEY_LENGTH) { 0x7F }, ContactType.REPEATER, 1.0, 2.0)

        assertEquals(
            LatLng(SENDER_LAT, SENDER_LON),
            resolveSenderLocation(senderPrefix, listOf(repeater, sender)),
        )
    }

    @Test
    fun `repeater-only list cannot resolve a chat sender`() {
        // The pre-fix behaviour: matching the sender against repeaters alone drops point A entirely.
        val sender = contact("Alice", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertNull(resolveSenderLocation(senderPrefix, repeaterContacts(listOf(sender))))
    }

    @Test
    fun `sender without a stored position plots nothing`() {
        val sender = contact("Alice", senderKey, ContactType.CHAT, 0.0, 0.0)
        assertNull(resolveSenderLocation(senderPrefix, listOf(sender)))
    }

    @Test
    fun `ambiguous prefix is not guessed at`() {
        val first = contact("Alice", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        val second = contact("Alicia", senderKey.copyOf().also { it[PREFIX_LENGTH] = 9 }, ContactType.CHAT, 3.0, 4.0)
        assertNull(resolveSenderLocation(senderPrefix, listOf(first, second)))
    }

    @Test
    fun `missing or empty sender prefix plots nothing`() {
        val sender = contact("Alice", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertNull(resolveSenderLocation(null, listOf(sender)))
        assertNull(resolveSenderLocation(ByteArray(0), listOf(sender)))
    }

    @Test
    fun `unique channel sender name with location plots point A`() {
        val sender = contact("RemoteNode", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertEquals(
            LatLng(SENDER_LAT, SENDER_LON),
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "RemoteNode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `unique channel sender name without location plots nothing`() {
        val sender = contact("RemoteNode", senderKey, ContactType.CHAT, 0.0, 0.0)
        assertNull(
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "RemoteNode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `two contacts sharing the channel sender name are not guessed at`() {
        val located = contact("Alice", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        val unlocated = contact(
            name = "Alice",
            key = ByteArray(PUBLIC_KEY_LENGTH) { 0xBB.toByte() },
            type = ContactType.CHAT,
            latitude = 0.0,
            longitude = 0.0,
        )
        assertNull(
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(located, unlocated),
                senderNodeName = "Alice",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `no contact matching the channel sender name plots nothing`() {
        val sender = contact("Alpha", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertNull(
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "RemoteNode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `key prefix wins over a different channel sender name`() {
        val prefixed = contact("Alpha", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        val named = contact(
            name = "RemoteNode",
            key = ByteArray(PUBLIC_KEY_LENGTH) { 0xBB.toByte() },
            type = ContactType.CHAT,
            latitude = 37.8,
            longitude = -122.5,
        )
        assertEquals(
            LatLng(SENDER_LAT, SENDER_LON),
            resolveSenderLocation(
                senderKeyPrefix = senderPrefix,
                contacts = listOf(prefixed, named),
                senderNodeName = "RemoteNode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `channel sender name matches case-insensitively`() {
        val sender = contact("RemoteNode", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertEquals(
            LatLng(SENDER_LAT, SENDER_LON),
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "remotenode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `unique blocked channel sender still plots`() {
        val sender = contact("RemoteNode", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
            .copy(isBlocked = true)
        assertEquals(
            LatLng(SENDER_LAT, SENDER_LON),
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "RemoteNode",
                isChannelMessage = true,
            ),
        )
    }

    @Test
    fun `channel sender name on a DM plots nothing`() {
        val sender = contact("RemoteNode", senderKey, ContactType.CHAT, SENDER_LAT, SENDER_LON)
        assertNull(
            resolveSenderLocation(
                senderKeyPrefix = null,
                contacts = listOf(sender),
                senderNodeName = "RemoteNode",
                isChannelMessage = false,
            ),
        )
    }

    private companion object {
        const val PUBLIC_KEY_LENGTH = 32
        const val PREFIX_LENGTH = 6
        const val SENDER_LAT = 52.37
        const val SENDER_LON = 4.9
    }
}
