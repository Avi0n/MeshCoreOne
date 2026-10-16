package one.meshcore.shared.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The master/scope preference gate that decides whether a preview shows and whether it auto-fetches. */
class LinkPreviewPreferencesTest {
    @Test
    fun shippingDefaultsArePreviewsOffAutoResolveOn() {
        val settings = InMemoryLinkPreviewSettings()
        assertFalse(settings.previewsEnabled)
        assertTrue(settings.autoResolveDM)
        assertTrue(settings.autoResolveChannels)
    }

    @Test
    fun masterOffSuppressesShowAndAutoResolve() {
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = false))
        assertFalse(prefs.shouldShowPreview)
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = false))
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = true))
    }

    @Test
    fun masterOnHonoursPerScopeAutoResolve() {
        val prefs = LinkPreviewPreferences(
            InMemoryLinkPreviewSettings(previewsEnabled = true, autoResolveDM = true, autoResolveChannels = false),
        )
        assertTrue(prefs.shouldShowPreview)
        assertTrue(prefs.shouldAutoResolve(isChannelMessage = false))
        // Master on + channel scope off → parked (tap-to-load), never auto-fetch.
        assertFalse(prefs.shouldAutoResolve(isChannelMessage = true))
    }
}
