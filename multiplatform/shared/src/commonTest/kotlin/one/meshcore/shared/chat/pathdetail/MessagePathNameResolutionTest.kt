package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.publicKey
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mirrors iOS `MessagePathViewModel.senderResolution` / `repeaterResolution`: a flood DM names the
 * sender from the contact book, and an unmatched hop is Unknown rather than hex.
 */
class MessagePathNameResolutionTest {
    private val unknown = "Unknown"

    @Test
    fun aChannelRowUsesTheWireSenderName() {
        val message = ChatFixtures.message(
            channelIndex = 1,
            contactID = null,
            senderNodeName = "Relay-7",
        )
        val resolution = MessagePathNameResolution.sender(message, contacts = emptyList(), unknown)
        assertEquals("Relay-7", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun aDirectMessageNamesTheSenderFromTheContactPrefix() {
        val key = publicKey(0xAB)
        val contact = contactDto(
            id = ContactTestIds.fresh(),
            radioID = ChatFixtures.RADIO,
            keyByte = 0xAB,
            name = "Alice",
        )
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = key.copyOf(6), senderNodeName = null)
        val resolution = MessagePathNameResolution.sender(message, listOf(contact), unknown)
        assertEquals("Alice", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun anUnknownDirectMessageSenderIsUnknownNotHex() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte()), senderNodeName = null)
        val resolution = MessagePathNameResolution.sender(message, contacts = emptyList(), unknown)
        assertEquals(unknown, resolution.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, resolution.matchKind)
    }

    @Test
    fun anUnmatchedRepeaterHopIsUnknownNotHex() {
        val resolution = MessagePathNameResolution.repeater(
            hashBytes = byteArrayOf(0xA3.toByte()),
            repeaters = emptyList(),
            discovered = emptyList(),
            userLocation = null,
            unknownLabel = unknown,
        )
        assertEquals(unknown, resolution.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, resolution.matchKind)
    }

    @Test
    fun aRepeaterHopNamesAMatchingSavedRepeater() {
        val repeater = contactDto(
            id = ContactTestIds.fresh(),
            radioID = ChatFixtures.RADIO,
            keyByte = 0xA3,
            name = "Hilltop",
            typeRawValue = ContactType.REPEATER.raw,
        )
        val resolution = MessagePathNameResolution.repeater(
            hashBytes = publicKey(0xA3).copyOf(1),
            repeaters = listOf(repeater),
            discovered = emptyList(),
            userLocation = null,
            unknownLabel = unknown,
        )
        assertEquals("Hilltop", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun pathReceiverPrefersTheCompanionFixThenThePhone() {
        val device = LatLng(1.0, 2.0)
        val phone = LatLng(3.0, 4.0)
        assertEquals(device, pathReceiverLocation(device, phone))
        assertEquals(phone, pathReceiverLocation(null, phone))
        assertNull(pathReceiverLocation(null, null))
    }
}
