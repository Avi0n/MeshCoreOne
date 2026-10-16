package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors iOS `MapFilterMigrationTests`: legacy discovered migrate, stored JSON wins, pure seed does
 * not write so a later restore can still land the legacy key.
 */
class MapFilterMigrationTest {
    @Test
    fun legacyOnlySeedsMainMapDiscoveredAndWrites() {
        val first = MapFilterPreferences.resolveMigrating(
            host = MapFilterHost.MAIN_MAP,
            storedRaw = null,
            legacyShowDiscovered = true,
        )
        assertTrue(first.state.showDiscovered)
        assertTrue(first.didWrite)

        val edited = first.state.setShowDiscovered(false)
        val again = MapFilterPreferences.resolveMigrating(
            host = MapFilterHost.MAIN_MAP,
            storedRaw = edited.toStorageString(),
            legacyShowDiscovered = true,
        )
        assertFalse(again.state.showDiscovered)
        assertFalse(again.didWrite)
    }

    @Test
    fun existingMapFilterWinsOverLegacyTrue() {
        val stored = MapFilterState(showDiscovered = false).toStorageString()
        val outcome = MapFilterPreferences.resolveMigrating(
            host = MapFilterHost.MAIN_MAP,
            storedRaw = stored,
            legacyShowDiscovered = true,
        )
        assertFalse(outcome.state.showDiscovered)
        assertFalse(outcome.didWrite)
    }

    @Test
    fun corruptStoredFilterReplacesItWithSeedWithoutRestoringLegacyToggle() {
        val outcome = MapFilterPreferences.resolveMigrating(
            host = MapFilterHost.MAIN_MAP,
            storedRaw = "{invalid",
            legacyShowDiscovered = true,
        )
        assertFalse(outcome.state.showDiscovered)
        assertTrue(outcome.didWrite)
    }

    @Test
    fun ensureMigratedLeavesEmptyRawWhenPureSeed() {
        val outcome = MapFilterPreferences.ensureMigrated(
            raw = "",
            host = MapFilterHost.MAIN_MAP,
            storedRaw = null,
            legacyShowDiscovered = null,
        )
        assertFalse(outcome.state.showDiscovered)
        assertFalse(outcome.didWrite)
    }

    @Test
    fun ensureMigratedAfterPureSeedThenLegacyRestoreSeedsDiscovered() {
        val seed = MapFilterPreferences.ensureMigrated(
            raw = "",
            host = MapFilterHost.MAIN_MAP,
            storedRaw = null,
            legacyShowDiscovered = null,
        )
        assertFalse(seed.didWrite)

        val restored = MapFilterPreferences.ensureMigrated(
            raw = "",
            host = MapFilterHost.MAIN_MAP,
            storedRaw = null,
            legacyShowDiscovered = true,
        )
        assertTrue(restored.state.showDiscovered)
        assertTrue(restored.didWrite)
    }

    @Test
    fun ensureMigratedLeavesValidRawUnchanged() {
        val original = MapFilterState(
            showDiscovered = true,
            showChat = false,
            showRepeater = true,
            showRoom = true,
        )
        val raw = original.toStorageString()
        val outcome = MapFilterPreferences.ensureMigrated(
            raw = raw,
            host = MapFilterHost.MAIN_MAP,
            storedRaw = raw,
            legacyShowDiscovered = null,
        )
        assertEquals(original, outcome.state)
        assertFalse(outcome.didWrite)
    }

    @Test
    fun ensureMigratedRewritesAllTypesOff() {
        val broken = MapFilterState(
            showDiscovered = true,
            showChat = false,
            showRepeater = false,
            showRoom = false,
        )
        val raw = broken.toStorageString()
        val outcome = MapFilterPreferences.ensureMigrated(
            raw = raw,
            host = MapFilterHost.MAIN_MAP,
            storedRaw = raw,
            legacyShowDiscovered = null,
        )
        assertTrue(outcome.state.showChat && outcome.state.showRepeater && outcome.state.showRoom)
        assertTrue(outcome.didWrite)
    }
}
