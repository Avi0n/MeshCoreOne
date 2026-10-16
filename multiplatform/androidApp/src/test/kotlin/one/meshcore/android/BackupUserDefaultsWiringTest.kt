package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BackupUserDefaults
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.backup.backupUserDefaultsSnapshot
import one.meshcore.shared.backup.restoreInto
import one.meshcore.shared.chat.reactions.RecentEmojisStore
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.notification.NotificationPreferenceKeys
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.settings.LinkPreviewScope
import one.meshcore.shared.settings.RegionSelectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the preference family the backup envelope carries against iOS `BackupUserDefaults`:
 * `snapshot(from:)` on export and the write-if-missing `restore(to:)` on import.
 */
class BackupUserDefaultsWiringTest {
    @Test
    fun snapshotCarriesTheRegionOnboardingAndNotificationPreferences() = runTest {
        val store = InMemoryPreferencesStore(
            mapOf(
                PersistenceKeys.HAS_COMPLETED_ONBOARDING to true,
                PersistenceKeys.REGION_SELECTION to REGION_JSON,
                PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS to STALE_DAYS,
                PersistenceKeys.SELECTED_THEME_ID to THEME_ID,
                NodeSortOrder.SORT_ORDER_PREFERENCE_KEY to SORT_ORDER,
                NotificationPreferenceKeys.CONTACT_MESSAGES to false,
                NotificationPreferenceKeys.SOUND to false,
                RecentEmojisStore.PREFS_KEY to RecentEmojisStore.encode(EMOJIS),
            ),
        )

        val snapshot = store.backupUserDefaultsSnapshot()

        assertEquals(true, snapshot.hasCompletedOnboarding)
        assertEquals(RegionSelection(COUNTRY_CODE, source = RegionSource.MANUAL), snapshot.regionSelection)
        assertEquals(STALE_DAYS, snapshot.autoDeleteStaleNodesDays)
        assertEquals(THEME_ID, snapshot.selectedThemeID)
        assertEquals(SORT_ORDER, snapshot.nodesSortOrder)
        assertEquals(false, snapshot.notifyContactMessages)
        assertEquals(false, snapshot.notificationSoundEnabled)
        assertEquals(EMOJIS, snapshot.recentEmojis)
    }

    @Test
    fun snapshotCarriesTheDiscoverSortOrderAndBothLinkPreviewScopes() = runTest {
        val store = InMemoryPreferencesStore(
            mapOf(
                NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY to DISCOVERY_SORT_ORDER,
                LinkPreviewScope.AUTO_RESOLVE_DM_KEY to false,
                LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY to false,
            ),
        )

        val snapshot = store.backupUserDefaultsSnapshot()

        assertEquals(DISCOVERY_SORT_ORDER, snapshot.discoverySortOrder)
        assertEquals(false, snapshot.linkPreviewsAutoResolveDM)
        assertEquals(false, snapshot.linkPreviewsAutoResolveChannels)
    }

    /**
     * The two scopes decide whether a conversation fetches remote content on its own. Dropping them
     * from a restore silently re-enables fetching the user had turned off, so they are carried like any
     * other preference rather than left to the default.
     */
    @Test
    fun restoreCarriesTheDiscoverSortOrderAndBothLinkPreviewScopes() = runTest {
        val store = InMemoryPreferencesStore()
        val envelope = BackupUserDefaults(
            discoverySortOrder = DISCOVERY_SORT_ORDER,
            linkPreviewsAutoResolveDM = false,
            linkPreviewsAutoResolveChannels = false,
        )

        val written = envelope.restoreInto(store) {}

        assertEquals(DISCOVERY_SORT_ORDER, store.getString(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY))
        assertEquals(false, store.getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY))
        assertEquals(false, store.getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY))
        assertTrue(
            "every carried preference must be written: $written",
            written.containsAll(
                listOf(
                    NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY,
                    LinkPreviewScope.AUTO_RESOLVE_DM_KEY,
                    LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY,
                ),
            ),
        )
    }

    @Test
    fun snapshotLeavesUnwrittenKeysNull() = runTest {
        val snapshot = InMemoryPreferencesStore().backupUserDefaultsSnapshot()

        assertNull(snapshot.hasCompletedOnboarding)
        assertNull(snapshot.regionSelection)
        assertNull(snapshot.recentEmojis)
        assertNull(snapshot.notifyContactMessages)
        assertNull(snapshot.autoDeleteStaleNodesDays)
    }

    @Test
    fun restoreWritesEveryAbsentKey() = runTest {
        val store = InMemoryPreferencesStore()
        val envelope = BackupUserDefaults(
            hasCompletedOnboarding = true,
            selectedThemeID = THEME_ID,
            autoDeleteStaleNodesDays = STALE_DAYS,
            nodesSortOrder = SORT_ORDER,
            notifyContactMessages = false,
            recentEmojis = EMOJIS,
        )

        val written = envelope.restoreInto(store) {}

        assertEquals(true, store.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING))
        assertEquals(THEME_ID, store.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertEquals(STALE_DAYS, store.getInt(PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS))
        assertEquals(SORT_ORDER, store.getString(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY))
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(EMOJIS, RecentEmojisStore.decode(store.getString(RecentEmojisStore.PREFS_KEY)))
        assertEquals(
            listOf(
                PersistenceKeys.HAS_COMPLETED_ONBOARDING,
                NotificationPreferenceKeys.CONTACT_MESSAGES,
                PersistenceKeys.SELECTED_THEME_ID,
                NodeSortOrder.SORT_ORDER_PREFERENCE_KEY,
                RecentEmojisStore.PREFS_KEY,
                PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS,
            ),
            written,
        )
    }

    @Test
    fun restoreNeverOverwritesALocallyChosenValue() = runTest {
        val store = InMemoryPreferencesStore(
            mapOf(
                PersistenceKeys.SELECTED_THEME_ID to THEME_ID,
                NotificationPreferenceKeys.CONTACT_MESSAGES to false,
                PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS to STALE_DAYS,
                PersistenceKeys.REGION_SELECTION to REGION_JSON,
            ),
        )
        val envelope = BackupUserDefaults(
            selectedThemeID = "foreign",
            notifyContactMessages = true,
            autoDeleteStaleNodesDays = STALE_DAYS + 1,
            regionSelection = RegionSelection("US", source = RegionSource.LOCATION),
        )

        val written = envelope.restoreInto(store) {}

        assertEquals(THEME_ID, store.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertEquals(false, store.getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES))
        assertEquals(STALE_DAYS, store.getInt(PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS))
        assertEquals(
            RegionSelection(COUNTRY_CODE, source = RegionSource.MANUAL),
            RegionSelectionStore.load(store),
        )
        assertTrue("nothing should have been written: $written", written.isEmpty())
    }

    @Test
    fun restoreIgnoresKeysTheEnvelopeDoesNotCarry() = runTest {
        val store = InMemoryPreferencesStore()

        val written = BackupUserDefaults().restoreInto(store) {}

        assertTrue(written.isEmpty())
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun exportedSnapshotRestoresOntoAFreshDevice() = runTest {
        val source = InMemoryPreferencesStore(
            mapOf(
                PersistenceKeys.HAS_COMPLETED_ONBOARDING to true,
                PersistenceKeys.MAP_STYLE_SELECTION to MAP_STYLE,
                PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP to true,
                PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE to COLOR_SCHEME,
                NotificationPreferenceKeys.LOW_BATTERY to false,
                RecentEmojisStore.PREFS_KEY to RecentEmojisStore.encode(EMOJIS),
            ),
        )
        val target = InMemoryPreferencesStore()

        source.backupUserDefaultsSnapshot().restoreInto(target) {}

        assertEquals(source.backupUserDefaultsSnapshot(), target.backupUserDefaultsSnapshot())
        assertFalse(target.snapshot().isEmpty())
    }

    private companion object {
        const val COUNTRY_CODE = "DE"
        const val REGION_JSON = """{"countryCode":"DE","source":"manual"}"""
        const val STALE_DAYS = 14
        const val THEME_ID = "local-theme"
        const val SORT_ORDER = "name"
        const val DISCOVERY_SORT_ORDER = "lastHeard"
        const val MAP_STYLE = "satellite"
        const val COLOR_SCHEME = "dark"
        val EMOJIS = listOf("🔥", "🎉")
    }
}
