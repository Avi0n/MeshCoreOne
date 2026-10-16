package one.meshcore.shared.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/** Pins the preference key strings to the exact on-disk names the iOS app uses. */
class PersistenceKeysTest {
    @Test
    fun connectionInfrastructureKeysUseTheReverseDnsPrefix() {
        assertEquals("com.pocketmesh.lastConnectedDeviceID", PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
        assertEquals("com.pocketmesh.lastConnectedDeviceName", PersistenceKeys.LAST_CONNECTED_DEVICE_NAME)
        assertEquals("com.pocketmesh.lastConnectedRadioID", PersistenceKeys.LAST_CONNECTED_RADIO_ID)
        assertEquals("com.pocketmesh.lastDisconnectDiagnostic", PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC)
        assertEquals("com.pocketmesh.userExplicitlyDisconnected", PersistenceKeys.USER_EXPLICITLY_DISCONNECTED)
    }

    @Test
    fun preferenceKeysUseTheBareCaseName() {
        assertEquals("hasCompletedOnboarding", PersistenceKeys.HAS_COMPLETED_ONBOARDING)
        assertEquals("autoDeleteStaleNodesDays", PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS)
        assertEquals("lastStaleCleanupDate", PersistenceKeys.LAST_STALE_CLEANUP_DATE)
        assertEquals("isDemoModeUnlocked", PersistenceKeys.IS_DEMO_MODE_UNLOCKED)
        assertEquals("isDemoModeEnabled", PersistenceKeys.IS_DEMO_MODE_ENABLED)
        assertEquals("userPrefs.region", PersistenceKeys.REGION_SELECTION)
        assertEquals("recentReactionEmojis", PersistenceKeys.RECENT_REACTION_EMOJIS)
        assertEquals("frequentEmojis", PersistenceKeys.FREQUENT_EMOJIS)
        assertEquals("hasSeenRepeaterDragHint", PersistenceKeys.HAS_SEEN_REPEATER_DRAG_HINT)
        assertEquals("translationTargetLanguage", PersistenceKeys.TRANSLATION_TARGET_LANGUAGE)
        assertEquals("useDefaultTranslationApp", PersistenceKeys.USE_DEFAULT_TRANSLATION_APP)
        assertEquals("tracePathViewMode", PersistenceKeys.TRACE_PATH_VIEW_MODE)
        assertEquals("mapFilterMainMap", PersistenceKeys.MAP_FILTER_MAIN_MAP)
        assertEquals("mapFilterTracePath", PersistenceKeys.MAP_FILTER_TRACE_PATH)
        assertEquals("mapFilterNeighborSNR", PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR)
        assertEquals("mapClusteringEnabled", PersistenceKeys.MAP_CLUSTERING_ENABLED)
        assertEquals("list", PersistenceKeys.TRACE_PATH_VIEW_MODE_LIST)
        assertEquals("map", PersistenceKeys.TRACE_PATH_VIEW_MODE_MAP)
    }

    @Test
    fun staleCleanupCooldownIsTheEnforcedThreeHours() {
        assertEquals(3.hours, PersistenceKeys.STALE_CLEANUP_COOLDOWN)
    }
}
