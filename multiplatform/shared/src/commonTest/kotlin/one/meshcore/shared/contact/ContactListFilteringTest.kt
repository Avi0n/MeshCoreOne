package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports the iOS `ContactsViewModel.filteredContacts` behavior: search spans all types by name or
 * key-prefix, segments select a type, the four sort orders, the distance→lastHeard fallback, and the
 * post-delete removal mask. Pure over [ContactListFiltering] so the ordering is pinned without a view model.
 */
class ContactListFilteringTest {
    private val radio = ContactTestIds.RADIO

    @Suppress("LongParameterList")
    private fun contact(
        keyByte: Int,
        name: String,
        type: ContactType = ContactType.CHAT,
        isFavorite: Boolean = false,
        outPathLength: Int = 0,
        lastModified: Long = 0,
    ) = contactDto(
        id = ContactTestIds.fresh(),
        radioID = radio,
        keyByte = keyByte,
        name = name,
        typeRawValue = type.raw,
        isFavorite = isFavorite,
        outPathLength = outPathLength,
    ).copy(lastModified = lastModified)

    @Suppress("LongParameterList")
    private fun filter(
        contacts: List<one.meshcore.shared.backup.ContactDto>,
        searchText: String = "",
        segment: NodeSegment = NodeSegment.CONTACTS,
        sortOrder: NodeSortOrder = NodeSortOrder.NAME,
        userLocation: UserLocation? = null,
        inboundHopByKey: Map<String, Int> = emptyMap(),
        pendingRemovalIds: Set<one.meshcore.shared.model.Uuid> = emptySet(),
    ) = ContactListFiltering.filteredContacts(
        ContactListFiltering.Inputs(
            contacts = contacts,
            searchText = searchText,
            segment = segment,
            sortOrder = sortOrder,
            userLocation = userLocation,
            inboundHopByKey = inboundHopByKey,
            pendingRemovalIds = pendingRemovalIds,
        ),
    )

    @Test
    fun searchIgnoresSegmentAndMatchesNameCaseInsensitively() {
        val alice = contact(0x01, "Alice", type = ContactType.CHAT)
        val bob = contact(0x02, "Bob", type = ContactType.REPEATER)
        val result = filter(listOf(alice, bob), searchText = "ali", segment = NodeSegment.ROOMS)
        // A repeater segment would exclude both, but a search spans all types and matches by name.
        assertEquals(listOf("Alice"), result.map { it.name })
    }

    @Test
    fun searchMatchesNameIgnoringDiacritics() {
        val jose = contact(0x01, "José", type = ContactType.CHAT)
        val bob = contact(0x02, "Bob", type = ContactType.REPEATER)
        val result = filter(listOf(jose, bob), searchText = "jose", segment = NodeSegment.ROOMS)
        assertEquals(listOf("José"), result.map { it.name })
    }

    @Test
    fun searchMatchesPublicKeyHexPrefix() {
        val alice = contact(0xAB, "Alice")
        val bob = contact(0xCD, "Bob")
        val result = filter(listOf(alice, bob), searchText = "abab")
        assertEquals(listOf("Alice"), result.map { it.name })
    }

    @Test
    fun segmentFiltersByType() {
        val chat = contact(0x01, "Chat", type = ContactType.CHAT)
        val repeater = contact(0x02, "Repeater", type = ContactType.REPEATER)
        val room = contact(0x03, "Room", type = ContactType.ROOM)
        val all = listOf(chat, repeater, room)
        assertEquals(listOf("Chat"), filter(all, segment = NodeSegment.CONTACTS).map { it.name })
        assertEquals(listOf("Repeater"), filter(all, segment = NodeSegment.REPEATERS).map { it.name })
        assertEquals(listOf("Room"), filter(all, segment = NodeSegment.ROOMS).map { it.name })
    }

    @Test
    fun favoritesSegmentKeepsOnlyFavorites() {
        val fav = contact(0x01, "Fav", type = ContactType.REPEATER, isFavorite = true)
        val plain = contact(0x02, "Plain", type = ContactType.CHAT, isFavorite = false)
        assertEquals(listOf("Fav"), filter(listOf(fav, plain), segment = NodeSegment.FAVORITES).map { it.name })
    }

    @Test
    fun nameSortIsCaseInsensitiveAscending() {
        val contacts = listOf(contact(0x01, "charlie"), contact(0x02, "Alice"), contact(0x03, "bob"))
        assertEquals(
            listOf("Alice", "bob", "charlie"),
            filter(contacts, sortOrder = NodeSortOrder.NAME).map { it.name },
        )
    }

    @Test
    fun lastHeardSortIsDescending() {
        val contacts = listOf(
            contact(0x01, "Old", lastModified = 100),
            contact(0x02, "New", lastModified = 300),
            contact(0x03, "Mid", lastModified = 200),
        )
        assertEquals(
            listOf("New", "Mid", "Old"),
            filter(contacts, sortOrder = NodeSortOrder.LAST_HEARD).map { it.name },
        )
    }

    @Test
    fun stalePruneUsesRecencyAndExemptsFavorites() {
        val cutoff = 1_000L
        val stale = contact(0x01, "Stale", lastModified = 100)
        val heardFresh = contact(0x02, "HeardFresh", lastModified = 100).copy(lastHeardTimestamp = 2_000)
        val favorite = contact(0x03, "Favorite", lastModified = 100).copy(isFavorite = true)
        assertTrue(stale.matchesStaleNodePrune(cutoff))
        assertFalse(heardFresh.matchesStaleNodePrune(cutoff))
        assertFalse(favorite.matchesStaleNodePrune(cutoff))
    }

    @Test
    fun recencyTimestampPrefersPhoneClockHeardOverRadioModified() {
        val radioOld = contact(0x01, "RadioOld", lastModified = 100)
        val heardNew = contact(0x02, "HeardNew", lastModified = 100).copy(lastHeardTimestamp = 400)
        assertEquals(100L, radioOld.recencyTimestamp)
        assertEquals(400L, heardNew.recencyTimestamp)
    }

    @Test
    fun heardOnAirSecondsHidesAMissingOrZeroStamp() {
        assertNull(contact(0x01, "Never").heardOnAirSeconds)
        assertNull(contact(0x02, "Zero").copy(lastHeardTimestamp = 0).heardOnAirSeconds)
        assertEquals(1_700L, contact(0x03, "Heard").copy(lastHeardTimestamp = 1_700).heardOnAirSeconds)
    }

    @Test
    fun lastHeardSortUsesPhoneClockWhenNewerThanRadioModified() {
        val radioOld = contact(0x01, "RadioOld", lastModified = 300)
        val heardNew = contact(0x02, "HeardNew", lastModified = 100).copy(lastHeardTimestamp = 400)
        assertEquals(
            listOf("HeardNew", "RadioOld"),
            filter(listOf(radioOld, heardNew), sortOrder = NodeSortOrder.LAST_HEARD).map { it.name },
        )
    }

    @Test
    fun hopsSortOrdersByHopCountWithUnknownLast() {
        val oneHop = contact(0x01, "OneHop", outPathLength = 0x01)
        val threeHops = contact(0x02, "ThreeHops", outPathLength = 0x03)
        val floodUnknown = contact(0x03, "Flood", outPathLength = CONTACT_FLOOD_PATH_SENTINEL)
        val result = filter(listOf(floodUnknown, threeHops, oneHop), sortOrder = NodeSortOrder.HOPS)
        assertEquals(listOf("OneHop", "ThreeHops", "Flood"), result.map { it.name })
    }

    @Test
    fun distanceSortFallsBackToLastHeardWithoutLocation() {
        val contacts = listOf(
            contact(0x01, "Old", lastModified = 100),
            contact(0x02, "New", lastModified = 300),
        )
        assertEquals(
            listOf("New", "Old"),
            filter(contacts, sortOrder = NodeSortOrder.DISTANCE, userLocation = null).map { it.name },
        )
    }

    @Test
    fun distanceSortPutsLocatedNodesNearestFirst() {
        val here = UserLocation(latitude = 0.0, longitude = 0.0)
        val near = contact(0x01, "Near").copy(latitude = 0.1, longitude = 0.1)
        val far = contact(0x02, "Far").copy(latitude = 5.0, longitude = 5.0)
        val noLocation = contact(0x03, "NoLocation")
        val result = filter(
            listOf(far, noLocation, near),
            sortOrder = NodeSortOrder.DISTANCE,
            userLocation = here,
        )
        assertEquals(listOf("Near", "Far", "NoLocation"), result.map { it.name })
    }

    @Test
    fun pendingRemovalMaskDropsRows() {
        val kept = contact(0x01, "Kept")
        val removed = contact(0x02, "Removed")
        val result = filter(listOf(kept, removed), pendingRemovalIds = setOf(removed.id))
        assertEquals(listOf("Kept"), result.map { it.name })
    }
}
