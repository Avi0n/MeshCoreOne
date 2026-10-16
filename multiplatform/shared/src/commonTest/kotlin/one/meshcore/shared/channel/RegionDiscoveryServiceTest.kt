package one.meshcore.shared.channel

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors iOS `RegionDiscoveryServiceTests` for query-target building (ad-hoc vs contact-only).
 */
class RegionDiscoveryServiceTest {
    private val radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000099")

    private fun key(byte: Int): ByteArray = ByteArray(32) { byte.toByte() }

    private fun repeaterContact(pub: ByteArray): ContactDto = ContactDto(
        id = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        radioID = radioID,
        publicKey = pub,
        name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw,
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
    )

    private fun repeaterNode(pub: ByteArray): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000002"),
        radioID = radioID,
        publicKey = pub,
        name = "Discovered",
        typeRawValue = ContactType.REPEATER.raw,
        lastHeardEpochSeconds = 0,
    )

    @Test
    fun nonContactRespondersQueriedWhenAdHocSupported() {
        val contactKey = key(0x11)
        val nonContactKey = key(0x22)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(contactKey, nonContactKey),
            contacts = listOf(repeaterContact(contactKey)),
            discoveredNodes = listOf(repeaterNode(nonContactKey)),
            supportsAdHocRequest = true,
        )
        val keys = targets.map { it.publicKey.toList() }.toSet()
        assertEquals(setOf(contactKey.toList(), nonContactKey.toList()), keys)
    }

    @Test
    fun nonContactRespondersSkippedWhenAdHocUnsupported() {
        val contactKey = key(0x11)
        val nonContactKey = key(0x22)
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders = setOf(contactKey, nonContactKey),
            contacts = listOf(repeaterContact(contactKey)),
            discoveredNodes = listOf(repeaterNode(nonContactKey)),
            supportsAdHocRequest = false,
        )
        assertEquals(1, targets.size)
        assertTrue(targets[0].publicKey.contentEquals(contactKey))
    }
}
