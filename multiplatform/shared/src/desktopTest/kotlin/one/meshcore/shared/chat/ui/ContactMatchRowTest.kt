package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.UserLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The block-sender and send-DM sheets exist because several stored contacts can share one channel sender
 * name, and the user has to pick which identity to act on. A row that carries only a name gives them
 * nothing to choose by, so each row must carry the same disambiguating detail as the Swift
 * `ContactMatchRow`: avatar, name, last-advert age, contact type, location with distance, and the full key.
 */
class ContactMatchRowTest {
    private fun contact(hasLocation: Boolean = false): ContactDto = ChatFixtures.contact("Alice").copy(
        latitude = if (hasLocation) LATITUDE else 0.0,
        longitude = if (hasLocation) LONGITUDE else 0.0,
        lastAdvertTimestamp = ADVERT_TIMESTAMP,
    )

    @Test
    fun aRowCarriesEveryDisambiguatingDetail() {
        val lines = contactMatchRowLines(contact(hasLocation = true), userLocation = NEARBY)
        assertEquals(
            listOf(
                ContactMatchRowLine.NAME,
                ContactMatchRowLine.LAST_ADVERT,
                ContactMatchRowLine.CONTACT_TYPE,
                ContactMatchRowLine.LOCATION,
                ContactMatchRowLine.DISTANCE,
                ContactMatchRowLine.PUBLIC_KEY,
            ),
            lines,
        )
    }

    @Test
    fun aContactWithNoCoordinateDropsTheLocationAndDistance() {
        val lines = contactMatchRowLines(contact(hasLocation = false), userLocation = NEARBY)
        assertTrue(ContactMatchRowLine.LOCATION !in lines)
        assertTrue(ContactMatchRowLine.DISTANCE !in lines)
        assertTrue(ContactMatchRowLine.PUBLIC_KEY in lines, "the key is the only unambiguous identity")
    }

    @Test
    fun anUnknownUserLocationKeepsThePinButDropsTheDistance() {
        val lines = contactMatchRowLines(contact(hasLocation = true), userLocation = null)
        assertTrue(ContactMatchRowLine.LOCATION in lines)
        assertTrue(ContactMatchRowLine.DISTANCE !in lines)
    }

    @Test
    fun theKeyIsRenderedAsSpacedUppercaseHex() {
        val keyed = ChatFixtures.contact("Alice").copy(publicKey = byteArrayOf(0x0A, 0xFF.toByte(), 0x10))
        assertEquals("0A FF 10", spacedKeyHex(keyed.publicKey))
    }

    @Test
    fun toggleSpeaksSelectedWhenChecked() {
        assertEquals(
            "Selected",
            contactMatchStateDescription(
                selection = ContactMatchSelection.Toggle(isSelected = true),
                selected = "Selected",
                notSelected = "Not selected",
            ),
        )
    }

    @Test
    fun toggleSpeaksNotSelectedWhenUnchecked() {
        assertEquals(
            "Not selected",
            contactMatchStateDescription(
                selection = ContactMatchSelection.Toggle(isSelected = false),
                selected = "Selected",
                notSelected = "Not selected",
            ),
        )
    }

    @Test
    fun tapHasNoSelectionValue() {
        assertNull(
            contactMatchStateDescription(
                selection = ContactMatchSelection.Tap,
                selected = "Selected",
                notSelected = "Not selected",
            ),
        )
    }

    private companion object {
        const val LATITUDE = 52.37
        const val LONGITUDE = 4.90
        const val ADVERT_TIMESTAMP = 1_704_067_200L
        val NEARBY = UserLocation(latitude = 52.38, longitude = 4.91)
    }
}
