package one.meshcore.shared.pathedit

import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `PickerNode` derived flags: favorite is contact-only, room reflects the contact type,
 * and discovered nodes report `isDiscovered`.
 */
class PickerNodeTest {
    @Test
    fun contactWithIsFavoriteTrueReportsFavorite() {
        val node = PickerNode.Contact(PathEditFixtures.contact(isFavorite = true))
        assertTrue(node.isFavorite)
        assertFalse(node.isDiscovered)
    }

    @Test
    fun discoveredNodeIsNeverFavorite() {
        val node = PickerNode.Discovered(PathEditFixtures.discovered())
        assertFalse(node.isFavorite)
        assertTrue(node.isDiscovered)
    }

    @Test
    fun roomContactReportsIsRoom() {
        val node = PickerNode.Contact(PathEditFixtures.contact(type = ContactType.ROOM))
        assertTrue(node.isRoom)
    }

    @Test
    fun displayNamePrefersNickname() {
        val node = PickerNode.Contact(PathEditFixtures.contact(name = "Advertised").copy(nickname = "Nick"))
        assertEquals("Nick", node.displayName)
    }
}
