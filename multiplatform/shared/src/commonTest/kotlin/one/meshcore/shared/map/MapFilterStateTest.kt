package one.meshcore.shared.map

import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors iOS `MapFilterStateTests` for defaults, seeds, sanitize, JSON, and type gates. */
class MapFilterStateTest {
    @Test
    fun defaultsMatchDesign() {
        val state = MapFilterState()
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat && state.showRepeater && state.showRoom)
    }

    @Test
    fun keepingChatPinsOnTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowChat(true, MapFilterHost.MAIN_MAP)

        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state, next)
    }

    @Test
    fun keepingDiscoveredNodesOffTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowDiscovered(false)

        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state, next)
    }

    @Test
    fun keepingMapFavoritesOffDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setFavoritesOnly(false)

        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state, next)
    }

    @Test
    fun turningOnMapFavoritesOnlyDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setFavoritesOnly(true)

        assertTrue(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state.copy(favoritesOnly = true), next)
    }

    @Test
    fun hidingChatPinsOnTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowChat(false, MapFilterHost.MAIN_MAP)

        assertFalse(next.showChat)
        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state.copy(showChat = false), next)
    }

    @Test
    fun hidingRepeaterPinsOnTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowRepeater(false, MapFilterHost.MAIN_MAP)

        assertTrue(next.showChat)
        assertFalse(next.showRepeater)
        assertTrue(next.showRoom)
        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertEquals(state.copy(showRepeater = false), next)
    }

    @Test
    fun hidingRoomPinsOnTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowRoom(false, MapFilterHost.MAIN_MAP)

        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertFalse(next.showRoom)
        assertFalse(next.favoritesOnly)
        assertFalse(next.showDiscovered)
        assertEquals(state.copy(showRoom = false), next)
    }

    @Test
    fun showingDiscoveredNodesOnTheMapFilterDoesNotRadio() {
        val state = MapFilterState.seed(MapFilterHost.MAIN_MAP)
        assertFalse(state.favoritesOnly)
        assertFalse(state.showDiscovered)
        assertTrue(state.showChat)
        assertTrue(state.showRepeater)
        assertTrue(state.showRoom)

        val next = state.setShowDiscovered(true)

        assertTrue(next.showDiscovered)
        assertFalse(next.favoritesOnly)
        assertTrue(next.showChat)
        assertTrue(next.showRepeater)
        assertTrue(next.showRoom)
        assertEquals(state.copy(showDiscovered = true), next)
    }

    @Test
    fun tracePathSeedTurnsDiscoveredOn() {
        val state = MapFilterState.seed(MapFilterHost.TRACE_PATH)
        assertTrue(state.showDiscovered)
        assertFalse(state.favoritesOnly)
    }

    @Test
    fun favoritesFreezesTypeAndDiscoveredFields() {
        var state = MapFilterState(showDiscovered = true, showChat = true, showRepeater = false, showRoom = true)
        state = state.setFavoritesOnly(true)
        assertTrue(state.favoritesOnly)
        assertTrue(state.showDiscovered)
        assertFalse(state.showRepeater)
        state = state.setShowDiscovered(false)
        state = state.setShowChat(false, MapFilterHost.MAIN_MAP)
        assertTrue(state.showDiscovered)
        assertTrue(state.showChat)
        state = state.setFavoritesOnly(false)
        assertFalse(state.favoritesOnly)
        assertTrue(state.showDiscovered)
        assertFalse(state.showRepeater)
    }

    @Test
    fun cannotClearLastEnabledType() {
        var state = MapFilterState(showChat = true, showRepeater = false, showRoom = false)
        state = state.setShowChat(false, MapFilterHost.MAIN_MAP)
        assertTrue(state.showChat)
    }

    @Test
    fun sanitizedRepairsAllTypesOff() {
        val raw = MapFilterState(
            favoritesOnly = false,
            showDiscovered = true,
            showChat = false,
            showRepeater = false,
            showRoom = false,
        )
        val state = raw.sanitized(MapFilterHost.MAIN_MAP)
        assertTrue(state.showChat && state.showRepeater && state.showRoom)
    }

    @Test
    fun emptyStorageStringIsNull() {
        assertNull(MapFilterState.fromStorageString(""))
    }

    @Test
    fun cannotClearLastEnabledRepeaterOrRoom() {
        val repeaterOnly = MapFilterState(showChat = false, showRepeater = true, showRoom = false)
            .setShowRepeater(false, MapFilterHost.MAIN_MAP)
        assertTrue(repeaterOnly.showRepeater)
        val roomOnly = MapFilterState(showChat = false, showRepeater = false, showRoom = true)
            .setShowRoom(false, MapFilterHost.MAIN_MAP)
        assertTrue(roomOnly.showRoom)
    }

    @Test
    fun typeSettersAreNoOpsOnHostsWithoutTypes() {
        val start = MapFilterState()
        val afterTrace = start.setShowChat(false, MapFilterHost.TRACE_PATH)
        val afterSnr = afterTrace.setShowRepeater(false, MapFilterHost.NEIGHBOR_SNR)
        assertTrue(afterSnr.showChat && afterSnr.showRepeater && afterSnr.showRoom)
    }

    @Test
    fun jsonRoundTrip() {
        val original = MapFilterState(
            favoritesOnly = true,
            showDiscovered = true,
            showChat = false,
            showRepeater = true,
            showRoom = true,
        )
        val decoded = MapFilterState.fromStorageString(original.toStorageString())
        assertEquals(original, decoded)
        assertNotNull(decoded)
    }

    @Test
    fun allowsContactTypeBypassesUnderFavorites() {
        val state = MapFilterState(favoritesOnly = true, showChat = false, showRepeater = false, showRoom = false)
        assertTrue(state.allowsContactType(ContactType.CHAT))
        assertTrue(state.allowsContactType(ContactType.REPEATER))
        assertTrue(state.allowsContactType(ContactType.ROOM))
    }

    @Test
    fun fromRawUsesHostSeedWhenMissingOrCorrupt() {
        val seed = MapFilterState.fromRaw(null, MapFilterHost.NEIGHBOR_SNR)
        assertFalse(seed.favoritesOnly)
        assertFalse(seed.showDiscovered)
        val corrupt = MapFilterState.fromRaw("{not-json", MapFilterHost.NEIGHBOR_SNR)
        assertEquals(seed, corrupt)
        val stored = MapFilterState(favoritesOnly = true).toStorageString()
        val decoded = MapFilterState.fromRaw(stored, MapFilterHost.NEIGHBOR_SNR)
        assertTrue(decoded.favoritesOnly)
    }

    @Test
    fun resolveMainMapAdoptsLegacyDiscoveredWhenJsonMissing() {
        val migrated = MapFilterState.resolveMainMap(raw = null, legacyShowDiscovered = true)
        assertTrue(migrated.showDiscovered)
        val seed = MapFilterState.resolveMainMap(raw = null, legacyShowDiscovered = false)
        assertFalse(seed.showDiscovered)
        val stored = MapFilterState(showDiscovered = false).toStorageString()
        val prefersStored = MapFilterState.resolveMainMap(raw = stored, legacyShowDiscovered = true)
        assertFalse(prefersStored.showDiscovered)
    }
}
