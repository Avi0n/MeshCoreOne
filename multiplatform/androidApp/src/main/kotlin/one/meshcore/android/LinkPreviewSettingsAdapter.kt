package one.meshcore.android

import one.meshcore.shared.chat.linkpreview.LinkPreviewSettings
import one.meshcore.shared.settings.ChatDisplayPreferencesStore

/**
 * Backs the link-preview gate with the persisted chat display toggles: the master switch plus the two
 * per-conversation-kind scopes. Reading them live means a scope turned off in Settings stops the next
 * auto-resolve in an already-open thread.
 */
internal fun linkPreviewSettings(preferences: ChatDisplayPreferencesStore): LinkPreviewSettings =
    object : LinkPreviewSettings {
        override var previewsEnabled: Boolean
            get() = preferences.state.value.linkPreviewsEnabled
            set(value) {
                preferences.setLinkPreviewsEnabled(value)
            }
        override var autoResolveDM: Boolean
            get() = preferences.state.value.linkPreviewsAutoResolveDM
            set(value) {
                preferences.setLinkPreviewsAutoResolveDM(value)
            }
        override var autoResolveChannels: Boolean
            get() = preferences.state.value.linkPreviewsAutoResolveChannels
            set(value) {
                preferences.setLinkPreviewsAutoResolveChannels(value)
            }
    }
