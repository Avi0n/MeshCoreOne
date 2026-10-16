package one.meshcore.shared.settings

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.hasTelemetryPermissions
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedContactsFilterTest {
    @Test
    fun chatOnlyAndFavoritesAndSearch() {
        val contacts = listOf(
            contact("a", "Alice", ContactType.CHAT, favorite = true, flags = 0),
            contact("b", "Bob", ContactType.CHAT, favorite = false, flags = 0x0E),
            contact("r", "Repeater", ContactType.REPEATER, favorite = true, flags = 0),
        )
        assertEquals(listOf("Alice", "Bob"), TrustedContactsFilter.filter(contacts, "", false).map { it.name })
        assertEquals(listOf("Alice"), TrustedContactsFilter.filter(contacts, "", true).map { it.name })
        assertEquals(listOf("Bob"), TrustedContactsFilter.filter(contacts, "bo", false).map { it.name })
    }

    @Test
    fun trustedIdsFromTelemetryFlags() {
        val contacts = listOf(
            contact("a", "Alice", ContactType.CHAT, flags = 0),
            contact("b", "Bob", ContactType.CHAT, flags = 0x0E),
            contact("c", "Carol", ContactType.CHAT, flags = 0x02),
        )
        val trusted = TrustedContactsFilter.trustedIds(contacts).map { it.value }.toSet()
        assertEquals(setOf("B", "C"), trusted)
        val bob = Uuid("B")
        assertEquals(
            setOf(bob),
            TrustedContactsFilter.toggledTrustedIds(selected = false, pending = emptySet(), id = bob),
        )
        assertEquals(
            emptySet(),
            TrustedContactsFilter.toggledTrustedIds(selected = true, pending = setOf(bob), id = bob),
        )
        assertFalse(hasTelemetryPermissions(0))
        assertTrue(hasTelemetryPermissions(0x0E))
        assertTrue(hasTelemetryPermissions(0x02))
    }

    private fun contact(
        id: String,
        name: String,
        type: ContactType,
        favorite: Boolean = false,
        flags: Int = 0,
    ): ContactDto = ContactDto(
        id = Uuid.normalized(id),
        radioID = Uuid.normalized("radio"),
        publicKey = ByteArray(32) { id.first().code.toByte() },
        name = name,
        typeRawValue = type.raw,
        flags = flags,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = favorite,
        unreadCount = 0,
    )
}
