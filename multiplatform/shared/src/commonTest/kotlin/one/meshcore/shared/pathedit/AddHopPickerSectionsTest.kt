package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `AddHopPickerView.buildResults` section rules: cross-section dedup only under `all`,
 * per-filter section visibility, rooms only under `all`, and query filtering across sections.
 */
class AddHopPickerSectionsTest {
    private fun repeater(byte: Int, name: String, favorite: Boolean = false) = PathEditFixtures.contact(
        name = name,
        publicKey = PathEditFixtures.key(byte),
        type = ContactType.REPEATER,
        isFavorite = favorite,
        id = PathEditFixtures.uuid(byte),
    )

    private fun room(byte: Int, name: String) = PathEditFixtures.contact(
        name = name,
        publicKey = PathEditFixtures.key(byte),
        type = ContactType.ROOM,
        id = PathEditFixtures.uuid(byte),
    )

    @Suppress("LongParameterList") // A test builder mirroring the picker inputs; grouping them adds no clarity.
    private fun sections(
        filter: AddHopFilter,
        query: String = "",
        repeaters: List<ContactDto> = emptyList(),
        rooms: List<ContactDto> = emptyList(),
        discovered: List<DiscoveredNode> = emptyList(),
        recent: List<ByteArray> = emptyList(),
    ) = AddHopPickerSections.build(
        state = PathEditState(availableRepeaters = repeaters, availableRooms = rooms, discoveredNodes = discovered),
        filter = filter,
        query = query,
        sessionRecentKeys = recent,
    )

    @Test
    fun allFilterDedupsRecentFromFavoritesAndContacts() {
        val fav = repeater(0x01, "Alpha", favorite = true)
        val plain = repeater(0x02, "Bravo")
        val result = sections(
            filter = AddHopFilter.ALL,
            repeaters = listOf(fav, plain),
            recent = listOf(PathEditFixtures.key(0x01)),
        )
        assertEquals(1, result.recent.size)
        assertEquals("Alpha", result.recent[0].displayName)
        assertTrue(result.favorites.isEmpty(), "recent key removed from favorites under All")
        assertEquals(listOf("Bravo"), result.contacts.map { it.displayName })
    }

    @Test
    fun singleFilterShowsSectionWithoutCrossDedup() {
        val fav = repeater(0x01, "Alpha", favorite = true)
        val result = sections(
            filter = AddHopFilter.FAVORITES,
            repeaters = listOf(fav),
            recent = listOf(PathEditFixtures.key(0x01)), // also recent, but no dedup in single filter
        )
        assertEquals(listOf("Alpha"), result.favorites.map { it.displayName })
        assertTrue(result.recent.isEmpty(), "recent section hidden under Favorites filter")
    }

    @Test
    fun roomsAppearOnlyUnderAll() {
        val room = room(0x05, "Base Room")
        assertEquals(1, sections(AddHopFilter.ALL, rooms = listOf(room)).rooms.size)
        assertTrue(sections(AddHopFilter.DISCOVERED, rooms = listOf(room)).rooms.isEmpty())
    }

    @Test
    fun discoveredDedupedAgainstContactsUnderAll() {
        val contact = repeater(0x03, "Charlie")
        val sameKey = PathEditFixtures.discovered(name = "Charlie-D", publicKey = PathEditFixtures.key(0x03))
        val other = PathEditFixtures.discovered(name = "Delta", publicKey = PathEditFixtures.key(0x04))
        val result = sections(AddHopFilter.ALL, repeaters = listOf(contact), discovered = listOf(sameKey, other))
        assertEquals(listOf("Delta"), result.discovered.map { it.displayName })
    }

    @Test
    fun queryFiltersSectionsByNameSubstring() {
        val result = sections(
            AddHopFilter.ALL,
            query = "brav",
            repeaters = listOf(repeater(0x01, "Alpha"), repeater(0x02, "Bravo")),
        )
        assertEquals(listOf("Bravo"), result.contacts.map { it.displayName })
    }

    @Test
    fun favoritesSearchDoesNotWidenToAllContacts() {
        val result = sections(
            filter = AddHopFilter.FAVORITES,
            query = "a",
            repeaters = listOf(
                repeater(0x01, "Alpha", favorite = true),
                repeater(0x02, "Bravo"),
            ),
        )
        assertEquals(listOf("Alpha"), result.favorites.map { it.displayName })
        assertTrue(result.contacts.isEmpty())
        assertTrue(result.recent.isEmpty())
    }
}
