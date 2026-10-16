package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PreferenceLoadGuard
import one.meshcore.shared.prefs.PreferencesStore

private typealias Field = ChatDisplayPreferenceField

/**
 * Live cache of [ChatDisplayPreferences] over [preferences]. [load] seeds the flow; setters write through
 * and update the cache so open chat threads see toggles without a process restart. Preview toggles
 * delegate to [ChatPreviewPreferenceWrites].
 */
class ChatDisplayPreferencesStore private constructor(private val parts: ChatDisplayPreferenceParts) :
    ChatPreviewPreferences by parts.previews {
    constructor(
        preferences: PreferencesStore,
        scope: CoroutineScope,
    ) : this(ChatDisplayPreferenceParts(preferences, scope))

    private val preferences = parts.preferences
    private val scope = parts.scope
    private val _state = parts.state
    val state: StateFlow<ChatDisplayPreferences> = _state.asStateFlow()

    fun load() {
        val loadSnapshot = parts.loadGuard.beginLoad()
        scope.launch {
            val loaded = preferences.loadChatDisplayPreferences()
            val current = _state.value
            _state.value = mergeLoadedChatDisplayPreferences(loaded, current, loadSnapshot, parts.loadGuard)
        }
    }

    fun setShowIncomingPath(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.INCOMING_PATH)
        _state.value = _state.value.copy(showIncomingPath = value)
        scope.launch { preferences.setShowIncomingPath(value) }
    }

    fun setShowIncomingHopCount(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.INCOMING_HOPS)
        _state.value = _state.value.copy(showIncomingHopCount = value)
        scope.launch { preferences.setShowIncomingHopCount(value) }
    }

    fun setShowIncomingRegion(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.INCOMING_REGION)
        _state.value = _state.value.copy(showIncomingRegion = value)
        scope.launch { preferences.setShowIncomingRegion(value) }
    }

    fun setShowIncomingHeardCount(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.INCOMING_HEARD_COUNT)
        _state.value = _state.value.copy(showIncomingHeardCount = value)
        scope.launch { preferences.setShowIncomingHeardCount(value) }
    }

    fun setShowIncomingSendTime(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.INCOMING_SEND_TIME)
        _state.value = _state.value.copy(showIncomingSendTime = value)
        scope.launch { preferences.setShowIncomingSendTime(value) }
    }

    fun setReplyWithQuote(value: Boolean) {
        parts.loadGuard.recordMutation(ChatDisplayPreferenceField.REPLY_WITH_QUOTE)
        _state.value = _state.value.copy(replyWithQuote = value)
        scope.launch { preferences.setReplyWithQuote(value) }
    }
}

private fun mergeLoadedChatDisplayPreferences(
    loaded: ChatDisplayPreferences,
    current: ChatDisplayPreferences,
    generation: PreferenceLoadGuard.Snapshot<ChatDisplayPreferenceField>,
    guard: PreferenceLoadGuard<ChatDisplayPreferenceField>,
): ChatDisplayPreferences {
    fun pick(field: Field, loadedValue: Boolean, currentValue: Boolean): Boolean =
        guard.resolve(generation, field, loadedValue, currentValue)

    return loaded.copy(
        showIncomingPath = pick(Field.INCOMING_PATH, loaded.showIncomingPath, current.showIncomingPath),
        showIncomingHopCount = pick(Field.INCOMING_HOPS, loaded.showIncomingHopCount, current.showIncomingHopCount),
        showIncomingRegion = pick(Field.INCOMING_REGION, loaded.showIncomingRegion, current.showIncomingRegion),
        showIncomingHeardCount = pick(
            Field.INCOMING_HEARD_COUNT,
            loaded.showIncomingHeardCount,
            current.showIncomingHeardCount,
        ),
        showIncomingSendTime = pick(
            Field.INCOMING_SEND_TIME,
            loaded.showIncomingSendTime,
            current.showIncomingSendTime,
        ),
        replyWithQuote = pick(Field.REPLY_WITH_QUOTE, loaded.replyWithQuote, current.replyWithQuote),
        linkPreviewsEnabled = pick(Field.LINK_PREVIEWS, loaded.linkPreviewsEnabled, current.linkPreviewsEnabled),
        linkPreviewsAutoResolveDM = pick(
            Field.AUTO_RESOLVE_DM,
            loaded.linkPreviewsAutoResolveDM,
            current.linkPreviewsAutoResolveDM,
        ),
        linkPreviewsAutoResolveChannels = pick(
            Field.AUTO_RESOLVE_CHANNELS,
            loaded.linkPreviewsAutoResolveChannels,
            current.linkPreviewsAutoResolveChannels,
        ),
        autoPlayGIFs = pick(Field.AUTO_PLAY_GIFS, loaded.autoPlayGIFs, current.autoPlayGIFs),
        showMapPreviewThumbnails = pick(
            Field.MAP_THUMBNAILS,
            loaded.showMapPreviewThumbnails,
            current.showMapPreviewThumbnails,
        ),
    )
}
