package one.meshcore.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-preview scope sub-toggles reaching the fetch gate: with previews on, a DM and a channel row
 * auto-resolve only while their own scope is on. Mirrors iOS `LinkPreviewSettingsSection`.
 */
class LinkPreviewScopeWiringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = ChatDisplayPreferencesStore(InMemoryPreferencesStore(), scope)
    private val preferences = LinkPreviewPreferences(linkPreviewSettings(store))

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun bothScopesAutoResolveWhilePreviewsAreOn() = runTest {
        store.setLinkPreviewsEnabled(true)

        assertTrue(preferences.shouldAutoResolve(isChannelMessage = false))
        assertTrue(preferences.shouldAutoResolve(isChannelMessage = true))
    }

    @Test
    fun aDmDoesNotAutoResolveWithTheDmScopeOff() = runTest {
        store.setLinkPreviewsEnabled(true)
        store.setLinkPreviewsAutoResolveDM(false)

        assertFalse(preferences.shouldAutoResolve(isChannelMessage = false))
        assertTrue(preferences.shouldAutoResolve(isChannelMessage = true))
    }

    @Test
    fun aChannelRowDoesNotAutoResolveWithTheChannelScopeOff() = runTest {
        store.setLinkPreviewsEnabled(true)
        store.setLinkPreviewsAutoResolveChannels(false)

        assertFalse(preferences.shouldAutoResolve(isChannelMessage = true))
        assertTrue(preferences.shouldAutoResolve(isChannelMessage = false))
    }

    @Test
    fun theMasterToggleStillOutranksBothScopes() = runTest {
        store.setLinkPreviewsEnabled(false)

        assertFalse(preferences.shouldAutoResolve(isChannelMessage = false))
        assertFalse(preferences.shouldAutoResolve(isChannelMessage = true))
    }
}
