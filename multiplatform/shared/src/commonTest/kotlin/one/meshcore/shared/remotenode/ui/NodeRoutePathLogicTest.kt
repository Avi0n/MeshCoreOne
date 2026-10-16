package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeRoutePathLogicTest {
    @Test
    fun floodSummaryUsesFloodLabel() {
        val contact = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        assertEquals(
            "No route set",
            NodeRoutePathLogic.summaryText(contact, floodLabel = "No route set", directLabel = "Direct"),
        )
    }

    @Test
    fun directSummaryUsesDirectLabel() {
        val contact = contact(outPathLength = PathEncoding.encodePathLen(1, 0))
        assertEquals(
            "Direct",
            NodeRoutePathLogic.summaryText(contact, floodLabel = "No route set", directLabel = "Direct"),
        )
    }

    @Test
    fun resolveHopsMatchesRepeaterPrefix() {
        val hopByte = 0xAB.toByte()
        val target = contact(
            outPathLength = PathEncoding.encodePathLen(1, 1),
            outPath = byteArrayOf(hopByte),
            publicKey = ByteArray(32) { 0x11 },
            name = "Target",
        )
        val repeaterKey = ByteArray(32) { 0x00 }.also { it[0] = hopByte }
        val repeater = contact(
            outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
            publicKey = repeaterKey,
            name = "Hilltop",
            type = ContactType.REPEATER,
        )
        val hops = NodeRoutePathLogic.resolveHops(target, listOf(repeater), unknownHopLabel = "<unknown>")
        assertEquals(1, hops.size)
        assertEquals("AB", hops.single().hex)
        assertEquals("Hilltop", hops.single().resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, hops.single().resolution.matchKind)
    }

    @Test
    fun resolveHopsUnknownWhenNoMatch() {
        val contact = contact(
            outPathLength = PathEncoding.encodePathLen(1, 1),
            outPath = byteArrayOf(0xCD.toByte()),
        )
        val hops = NodeRoutePathLogic.resolveHops(contact, emptyList(), unknownHopLabel = "<unknown>")
        assertEquals(1, hops.size)
        assertEquals("<unknown>", hops.single().resolution.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, hops.single().resolution.matchKind)
    }

    @Test
    fun resolveHopsNamesARepeaterKnownOnlyFromAnAdvert() {
        val hopByte = 0xEF.toByte()
        val target = contact(
            outPathLength = PathEncoding.encodePathLen(1, 1),
            outPath = byteArrayOf(hopByte),
            publicKey = ByteArray(32) { 0x11 },
            name = "Target",
        )
        val heard = DiscoveredNodeRecord(
            id = Uuid.normalized("D0000000-0000-0000-0000-000000000010"),
            radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000010"),
            publicKey = ByteArray(32) { 0x00 }.also { it[0] = hopByte },
            name = "Ridge Relay",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 0,
        )
        val hops = NodeRoutePathLogic.resolveHops(target, emptyList(), "<unknown>", discovered = listOf(heard))
        assertEquals("Ridge Relay", hops.single().resolution.displayName)
    }

    @Test
    fun resolveHopsIgnoresADiscoveredNonRepeater() {
        // Only repeaters relay, so a chat node sharing the hop prefix must not claim the hop.
        val hopByte = 0xFE.toByte()
        val target = contact(
            outPathLength = PathEncoding.encodePathLen(1, 1),
            outPath = byteArrayOf(hopByte),
            publicKey = ByteArray(32) { 0x11 },
            name = "Target",
        )
        val heard = DiscoveredNodeRecord(
            id = Uuid.normalized("D0000000-0000-0000-0000-000000000011"),
            radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000010"),
            publicKey = ByteArray(32) { 0x00 }.also { it[0] = hopByte },
            name = "Just A Phone",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 0,
        )
        val hops = NodeRoutePathLogic.resolveHops(target, emptyList(), "<unknown>", discovered = listOf(heard))
        assertEquals("<unknown>", hops.single().resolution.displayName)
    }

    @Test
    fun floodHasNoResolvedHops() {
        val contact = contact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        assertTrue(NodeRoutePathLogic.resolveHops(contact, emptyList(), "<unknown>").isEmpty())
    }

    private fun contact(
        outPathLength: Int,
        outPath: ByteArray = ByteArray(0),
        publicKey: ByteArray = ByteArray(32) { 1 },
        name: String = "Node",
        type: ContactType = ContactType.REPEATER,
    ): ContactDto = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000010"),
        radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000010"),
        publicKey = publicKey,
        name = name,
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = outPathLength,
        outPath = outPath,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
