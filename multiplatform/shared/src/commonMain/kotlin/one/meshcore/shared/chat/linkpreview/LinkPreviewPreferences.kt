package one.meshcore.shared.chat.linkpreview

/**
 * The three persisted link-preview flags, mirroring the Swift `AppStorageKey` defaults. There is no
 * per-individual-chat flag; "per conversation" means per conversation *type* (DM vs channel). The master
 * [previewsEnabled] gates link cards AND inline images and defaults **off**; the two auto-resolve flags
 * default **on**. The production adapter reads and writes shared settings. `previewsEnabled` round-trips
 * in the backup envelope, but a legacy envelope must never set it.
 */
interface LinkPreviewSettings {
    var previewsEnabled: Boolean
    var autoResolveDM: Boolean
    var autoResolveChannels: Boolean

    companion object {
        const val DEFAULT_PREVIEWS_ENABLED: Boolean = false
        const val DEFAULT_AUTO_RESOLVE_DM: Boolean = true
        const val DEFAULT_AUTO_RESOLVE_CHANNELS: Boolean = true
    }
}

/**
 * The preference gate for link-preview fetching, a verbatim port of the Swift `LinkPreviewPreferences`.
 * [shouldShowPreview] gates whether preview UI appears at all; [shouldAutoResolve] decides whether a
 * preview auto-fetches for a message's conversation type. Master-on with the scope flag off yields a parked
 * "tap-to-load" card (never an auto-fetch), and this same gate feeds the inline-image probe privacy check.
 */
class LinkPreviewPreferences(private val settings: LinkPreviewSettings) {
    /** Whether previews should be shown at all (the master toggle). */
    val shouldShowPreview: Boolean
        get() = settings.previewsEnabled

    /** Whether a preview should auto-resolve for [isChannelMessage]; false when the master toggle is off. */
    fun shouldAutoResolve(isChannelMessage: Boolean): Boolean {
        if (!settings.previewsEnabled) return false
        return if (isChannelMessage) settings.autoResolveChannels else settings.autoResolveDM
    }
}
