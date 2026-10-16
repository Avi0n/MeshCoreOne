package one.meshcore.shared.settings

import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Chat bubble display toggles persisted in [PreferencesStore] with iOS-aligned keys
 * (`showIncoming*`, `linkPreviewsEnabled`). Defaults match AppStorageKey (all off).
 */
data class ChatDisplayPreferences(
    val showIncomingPath: Boolean = PersistenceKeys.DEFAULT_SHOW_INCOMING_PATH,
    val showIncomingHopCount: Boolean = PersistenceKeys.DEFAULT_SHOW_INCOMING_HOP_COUNT,
    val showIncomingRegion: Boolean = PersistenceKeys.DEFAULT_SHOW_INCOMING_REGION,
    val showIncomingHeardCount: Boolean = PersistenceKeys.DEFAULT_SHOW_INCOMING_HEARD_COUNT,
    val showIncomingSendTime: Boolean = PersistenceKeys.DEFAULT_SHOW_INCOMING_SEND_TIME,
    val replyWithQuote: Boolean = PersistenceKeys.DEFAULT_REPLY_WITH_QUOTE,
    val linkPreviewsEnabled: Boolean = PersistenceKeys.DEFAULT_LINK_PREVIEWS_ENABLED,
    val linkPreviewsAutoResolveDM: Boolean = LinkPreviewScope.DEFAULT_AUTO_RESOLVE_DM,
    val linkPreviewsAutoResolveChannels: Boolean = LinkPreviewScope.DEFAULT_AUTO_RESOLVE_CHANNELS,
    val autoPlayGIFs: Boolean = PersistenceKeys.DEFAULT_AUTO_PLAY_GIFS,
    val showMapPreviewThumbnails: Boolean = PersistenceKeys.DEFAULT_SHOW_MAP_PREVIEW_THUMBNAILS,
)

/**
 * Which conversations may fetch link content once the master toggle is on. Both scopes default on, so
 * the master toggle alone decides fetching until the user narrows it; keys and defaults are pinned to
 * the Swift `AppStorageKey.linkPreviewsAutoResolveDM` / `…Channels` pair.
 */
object LinkPreviewScope {
    const val AUTO_RESOLVE_DM_KEY = "linkPreviewsAutoResolveDM"
    const val AUTO_RESOLVE_CHANNELS_KEY = "linkPreviewsAutoResolveChannels"
    const val DEFAULT_AUTO_RESOLVE_DM = true
    const val DEFAULT_AUTO_RESOLVE_CHANNELS = true
}

/** Loads [ChatDisplayPreferences] from [store]; missing keys use the defaults. */
suspend fun PreferencesStore.loadChatDisplayPreferences(): ChatDisplayPreferences = ChatDisplayPreferences(
    showIncomingPath = getBoolean(PersistenceKeys.SHOW_INCOMING_PATH)
        ?: PersistenceKeys.DEFAULT_SHOW_INCOMING_PATH,
    showIncomingHopCount = getBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT)
        ?: PersistenceKeys.DEFAULT_SHOW_INCOMING_HOP_COUNT,
    showIncomingRegion = getBoolean(PersistenceKeys.SHOW_INCOMING_REGION)
        ?: PersistenceKeys.DEFAULT_SHOW_INCOMING_REGION,
    showIncomingHeardCount = getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT)
        ?: PersistenceKeys.DEFAULT_SHOW_INCOMING_HEARD_COUNT,
    showIncomingSendTime = getBoolean(PersistenceKeys.SHOW_INCOMING_SEND_TIME)
        ?: PersistenceKeys.DEFAULT_SHOW_INCOMING_SEND_TIME,
    replyWithQuote = getBoolean(PersistenceKeys.REPLY_WITH_QUOTE)
        ?: PersistenceKeys.DEFAULT_REPLY_WITH_QUOTE,
    linkPreviewsEnabled = getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED)
        ?: PersistenceKeys.DEFAULT_LINK_PREVIEWS_ENABLED,
    linkPreviewsAutoResolveDM = getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY)
        ?: LinkPreviewScope.DEFAULT_AUTO_RESOLVE_DM,
    linkPreviewsAutoResolveChannels = getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY)
        ?: LinkPreviewScope.DEFAULT_AUTO_RESOLVE_CHANNELS,
    autoPlayGIFs = getBoolean(PersistenceKeys.AUTO_PLAY_GIFS)
        ?: PersistenceKeys.DEFAULT_AUTO_PLAY_GIFS,
    showMapPreviewThumbnails = getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS)
        ?: PersistenceKeys.DEFAULT_SHOW_MAP_PREVIEW_THUMBNAILS,
)

suspend fun PreferencesStore.setShowIncomingPath(value: Boolean) = putBoolean(PersistenceKeys.SHOW_INCOMING_PATH, value)

suspend fun PreferencesStore.setShowIncomingHopCount(value: Boolean) =
    putBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT, value)

suspend fun PreferencesStore.setShowIncomingRegion(value: Boolean) =
    putBoolean(PersistenceKeys.SHOW_INCOMING_REGION, value)

suspend fun PreferencesStore.setShowIncomingHeardCount(value: Boolean) =
    putBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT, value)

suspend fun PreferencesStore.setShowIncomingSendTime(value: Boolean) =
    putBoolean(PersistenceKeys.SHOW_INCOMING_SEND_TIME, value)

suspend fun PreferencesStore.setReplyWithQuote(value: Boolean) = putBoolean(PersistenceKeys.REPLY_WITH_QUOTE, value)
