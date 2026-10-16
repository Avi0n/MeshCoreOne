package one.meshcore.shared.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationConfigReviewRecoveryTest {
    @Test
    fun aReturnedDocumentReviewSurvivesDeviceTeardown() = runTest {
        val nav = NavigationCoordinator(backgroundScope)
        nav.navigateToSettings(SettingsDestination.CONFIG_IMPORT)
        nav.clearPerDeviceSelection()
        assertEquals(SettingsDestination.CONFIG_IMPORT, nav.selectedSetting.value)
        assertEquals(AppTab.SETTINGS.index, nav.selectedTab.value)
    }

    @Test
    fun delayedStoredTabCannotReplaceAnExplicitSettingsRoute() = runTest {
        for (destination in listOf(SettingsDestination.CONFIG_IMPORT, SettingsDestination.BACKUP)) {
            val release = CompletableDeferred<Unit>()
            val backing = InMemoryPreferencesStore(
                mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.TOOLS.index),
            )
            val prefs = object : PreferencesStore by backing {
                override suspend fun getInt(key: String): Int? {
                    if (key == NavigationCoordinator.SELECTED_TAB_KEY) release.await()
                    return backing.getInt(key)
                }
            }
            val nav = NavigationCoordinator(backgroundScope, prefs)
            runCurrent()
            nav.navigateToSettings(destination)
            release.complete(Unit)
            runCurrent()
            assertEquals(destination, nav.selectedSetting.value)
            assertEquals(AppTab.SETTINGS.index, nav.selectedTab.value)
            assertEquals(AppTab.SETTINGS.index, backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        }
    }

    @Test
    fun ordinaryStoredTabRestorationStillWorks() = runTest {
        val backing = InMemoryPreferencesStore(
            mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.TOOLS.index),
        )
        val nav = NavigationCoordinator(backgroundScope, backing)
        runCurrent()
        assertEquals(AppTab.TOOLS.index, nav.selectedTab.value)
    }

    @Test
    fun explicitReviewWithStoredSettingsRemainsSelected() = runTest {
        val release = CompletableDeferred<Unit>()
        val backing = InMemoryPreferencesStore(
            mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.SETTINGS.index),
        )
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) release.await()
                return backing.getInt(key)
            }
        }
        val nav = NavigationCoordinator(backgroundScope, prefs)
        runCurrent()
        nav.navigateToSettings(SettingsDestination.CONFIG_IMPORT)
        release.complete(Unit)
        runCurrent()
        assertEquals(SettingsDestination.CONFIG_IMPORT, nav.selectedSetting.value)
        assertEquals(AppTab.SETTINGS.index, nav.selectedTab.value)
    }
}
