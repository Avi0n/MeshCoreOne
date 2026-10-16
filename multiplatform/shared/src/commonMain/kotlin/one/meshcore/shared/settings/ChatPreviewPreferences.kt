package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore

/** Link-preview, GIF, and map-thumbnail toggles on [ChatDisplayPreferencesStore]. */
interface ChatPreviewPreferences {
    fun setLinkPreviewsEnabled(value: Boolean)

    fun setLinkPreviewsAutoResolveDM(value: Boolean)

    fun setLinkPreviewsAutoResolveChannels(value: Boolean)

    fun setAutoPlayGIFs(value: Boolean)

    fun setShowMapPreviewThumbnails(value: Boolean)
}

internal class ChatDisplayPreferenceParts(val preferences: PreferencesStore, val scope: CoroutineScope) {
    val state = MutableStateFlow(ChatDisplayPreferences())
    val loadGuard = PreferenceLoadGuard<ChatDisplayPreferenceField>()
    val previews = ChatPreviewPreferenceWrites(preferences, scope, state, loadGuard)
}

internal class ChatPreviewPreferenceWrites(
    private val preferences: PreferencesStore,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatDisplayPreferences>,
    private val loadGuard: PreferenceLoadGuard<ChatDisplayPreferenceField>,
) : ChatPreviewPreferences {
    override fun setLinkPreviewsEnabled(value: Boolean) {
        loadGuard.recordMutation(ChatDisplayPreferenceField.LINK_PREVIEWS)
        state.value = state.value.copy(linkPreviewsEnabled = value)
        scope.launch { preferences.setLinkPreviewsEnabled(value) }
    }

    override fun setLinkPreviewsAutoResolveDM(value: Boolean) {
        loadGuard.recordMutation(ChatDisplayPreferenceField.AUTO_RESOLVE_DM)
        state.value = state.value.copy(linkPreviewsAutoResolveDM = value)
        scope.launch { preferences.setLinkPreviewsAutoResolveDM(value) }
    }

    override fun setLinkPreviewsAutoResolveChannels(value: Boolean) {
        loadGuard.recordMutation(ChatDisplayPreferenceField.AUTO_RESOLVE_CHANNELS)
        state.value = state.value.copy(linkPreviewsAutoResolveChannels = value)
        scope.launch { preferences.setLinkPreviewsAutoResolveChannels(value) }
    }

    override fun setAutoPlayGIFs(value: Boolean) {
        loadGuard.recordMutation(ChatDisplayPreferenceField.AUTO_PLAY_GIFS)
        state.value = state.value.copy(autoPlayGIFs = value)
        scope.launch { preferences.setAutoPlayGIFs(value) }
    }

    override fun setShowMapPreviewThumbnails(value: Boolean) {
        loadGuard.recordMutation(ChatDisplayPreferenceField.MAP_THUMBNAILS)
        state.value = state.value.copy(showMapPreviewThumbnails = value)
        scope.launch { preferences.setShowMapPreviewThumbnails(value) }
    }
}

internal enum class ChatDisplayPreferenceField {
    INCOMING_PATH,
    INCOMING_HOPS,
    INCOMING_REGION,
    INCOMING_HEARD_COUNT,
    INCOMING_SEND_TIME,
    REPLY_WITH_QUOTE,
    LINK_PREVIEWS,
    AUTO_RESOLVE_DM,
    AUTO_RESOLVE_CHANNELS,
    AUTO_PLAY_GIFS,
    MAP_THUMBNAILS,
}

suspend fun PreferencesStore.setLinkPreviewsEnabled(value: Boolean) =
    putBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED, value)

suspend fun PreferencesStore.setLinkPreviewsAutoResolveDM(value: Boolean) =
    putBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY, value)

suspend fun PreferencesStore.setLinkPreviewsAutoResolveChannels(value: Boolean) =
    putBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY, value)

suspend fun PreferencesStore.setAutoPlayGIFs(value: Boolean) = putBoolean(PersistenceKeys.AUTO_PLAY_GIFS, value)

suspend fun PreferencesStore.setShowMapPreviewThumbnails(value: Boolean) =
    putBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS, value)
