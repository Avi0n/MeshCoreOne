package one.meshcore.shared.backup

import one.meshcore.shared.chat.reactions.RecentEmojisStore
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.notification.NotificationPreferenceKeys
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.prefs.runPreferences
import one.meshcore.shared.settings.LinkPreviewScope
import one.meshcore.shared.settings.RegionSelectionStore

/**
 * Reads the preference family a backup carries, mirroring Swift `BackupUserDefaults.snapshot(from:)`.
 * A key that was never written stays null, so the envelope keeps "never set" distinguishable from an
 * explicit value — the distinction the write-if-missing restore keys off. Fields whose iOS preference
 * has no Android home yet stay null rather than inventing a value for them.
 */
suspend fun PreferencesStore.backupUserDefaultsSnapshot(): BackupUserDefaults = BackupUserDefaults(
    hasCompletedOnboarding = getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING),
    mapStyleSelection = getString(PersistenceKeys.MAP_STYLE_SELECTION),
    mapFilterMainMap = getString(PersistenceKeys.MAP_FILTER_MAIN_MAP),
    mapFilterTracePath = getString(PersistenceKeys.MAP_FILTER_TRACE_PATH),
    mapFilterNeighborSNR = getString(PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR),
    selectedThemeID = getString(PersistenceKeys.SELECTED_THEME_ID),
    appColorSchemePreference = getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE),
    mapShowLabels = getBoolean(PersistenceKeys.MAP_SHOW_LABELS),
    mapClusteringEnabled = getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED),
    mapNorthLocked = getBoolean(PersistenceKeys.MAP_NORTH_LOCKED),
    showDiscoveredNodesOnMap = getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP),
    mapColorSchemePreference = getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE),
    replyWithQuote = getBoolean(PersistenceKeys.REPLY_WITH_QUOTE),
    autoPlayGIFs = getBoolean(PersistenceKeys.AUTO_PLAY_GIFS),
    showIncomingPath = getBoolean(PersistenceKeys.SHOW_INCOMING_PATH),
    showIncomingHopCount = getBoolean(PersistenceKeys.SHOW_INCOMING_HOP_COUNT),
    showIncomingRegion = getBoolean(PersistenceKeys.SHOW_INCOMING_REGION),
    showIncomingHeardCount = getBoolean(PersistenceKeys.SHOW_INCOMING_HEARD_COUNT),
    showIncomingSendTime = getBoolean(PersistenceKeys.SHOW_INCOMING_SEND_TIME),
    autoDeleteStaleNodesDays = getInt(PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS),
    discoverySortOrder = getString(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY),
    nodesSortOrder = getString(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY),
    linkPreviewsEnabled = getBoolean(PersistenceKeys.LINK_PREVIEWS_ENABLED),
    linkPreviewsAutoResolveDM = getBoolean(LinkPreviewScope.AUTO_RESOLVE_DM_KEY),
    linkPreviewsAutoResolveChannels = getBoolean(LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY),
    showMapPreviewThumbnails = getBoolean(PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS),
    recentEmojis = getString(RecentEmojisStore.PREFS_KEY)?.let(RecentEmojisStore::decode),
    frequentEmojis = getString(RecentEmojisStore.FREQUENT_PREFS_KEY)?.let {
        RecentEmojisStore.decode(it, RecentEmojisStore.MAX_FREQUENT)
    },
    hasSeenRepeaterDragHint = getBoolean(PersistenceKeys.HAS_SEEN_REPEATER_DRAG_HINT),
    translationTargetLanguage = getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE),
    translationOffersEnabled = getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED),
    useDefaultTranslationApp = getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP),
    tracePathViewMode = getString(PersistenceKeys.TRACE_PATH_VIEW_MODE),
    regionSelection = RegionSelectionStore.load(this),
    notifyContactMessages = getBoolean(NotificationPreferenceKeys.CONTACT_MESSAGES),
    notifyChannelMessages = getBoolean(NotificationPreferenceKeys.CHANNEL_MESSAGES),
    notifyRoomMessages = getBoolean(NotificationPreferenceKeys.ROOM_MESSAGES),
    notifyNewContacts = getBoolean(NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED),
    notifyNewContactsContact = getBoolean(NotificationPreferenceKeys.DISCOVERY_CONTACT),
    notifyNewContactsRepeater = getBoolean(NotificationPreferenceKeys.DISCOVERY_REPEATER),
    notifyNewContactsRoom = getBoolean(NotificationPreferenceKeys.DISCOVERY_ROOM),
    notifyReactions = getBoolean(NotificationPreferenceKeys.REACTIONS),
    notificationSoundEnabled = getBoolean(NotificationPreferenceKeys.SOUND),
    notificationBadgeEnabled = getBoolean(NotificationPreferenceKeys.BADGE),
    notifyLowBattery = getBoolean(NotificationPreferenceKeys.LOW_BATTERY),
)

/**
 * Restores the preference family write-if-missing, mirroring Swift `BackupUserDefaults.restore(to:)`:
 * a value from the envelope is adopted only where the local key has never been written, so a choice the
 * user already made on this device is never overwritten. Absence, not the value, is the test — an
 * explicitly chosen default is still a choice and stays.
 *
 * @return the keys newly written, in the order they were applied.
 */
suspend fun BackupUserDefaults.restoreInto(store: PreferencesStore, warning: (String) -> Unit): List<String> {
    val written = mutableListOf<String>()
    restoreAbsentBooleans(store, written, warning)
    restoreAbsentStrings(store, written, warning)
    restoreAbsentRegion(store, written, warning)
    restoreAbsentStaleDays(store, written, warning)
    return written
}

private suspend fun BackupUserDefaults.restoreAbsentBooleans(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
) {
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.HAS_COMPLETED_ONBOARDING, hasCompletedOnboarding)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.MAP_SHOW_LABELS, mapShowLabels)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.MAP_CLUSTERING_ENABLED, mapClusteringEnabled)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.MAP_NORTH_LOCKED, mapNorthLocked)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP, showDiscoveredNodesOnMap)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.REPLY_WITH_QUOTE, replyWithQuote)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.AUTO_PLAY_GIFS, autoPlayGIFs)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_INCOMING_PATH, showIncomingPath)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_INCOMING_HOP_COUNT, showIncomingHopCount)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_INCOMING_REGION, showIncomingRegion)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_INCOMING_HEARD_COUNT, showIncomingHeardCount)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_INCOMING_SEND_TIME, showIncomingSendTime)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.LINK_PREVIEWS_ENABLED, linkPreviewsEnabled)
    // Both scopes gate remote fetches on their own. Dropping them from a restore turns fetching back
    // on for a user who narrowed it, so they travel with the master switch rather than defaulting.
    putBooleanIfAbsent(store, written, warning, LinkPreviewScope.AUTO_RESOLVE_DM_KEY, linkPreviewsAutoResolveDM)
    putBooleanIfAbsent(
        store,
        written,
        warning,
        LinkPreviewScope.AUTO_RESOLVE_CHANNELS_KEY,
        linkPreviewsAutoResolveChannels,
    )
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.SHOW_MAP_PREVIEW_THUMBNAILS, showMapPreviewThumbnails)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.HAS_SEEN_REPEATER_DRAG_HINT, hasSeenRepeaterDragHint)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.USE_DEFAULT_TRANSLATION_APP, useDefaultTranslationApp)
    putBooleanIfAbsent(store, written, warning, PersistenceKeys.TRANSLATION_OFFERS_ENABLED, translationOffersEnabled)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.CONTACT_MESSAGES, notifyContactMessages)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.CHANNEL_MESSAGES, notifyChannelMessages)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.ROOM_MESSAGES, notifyRoomMessages)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.NEW_CONTACT_DISCOVERED, notifyNewContacts)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.DISCOVERY_CONTACT, notifyNewContactsContact)
    putBooleanIfAbsent(
        store,
        written,
        warning,
        NotificationPreferenceKeys.DISCOVERY_REPEATER,
        notifyNewContactsRepeater,
    )
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.DISCOVERY_ROOM, notifyNewContactsRoom)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.REACTIONS, notifyReactions)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.SOUND, notificationSoundEnabled)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.BADGE, notificationBadgeEnabled)
    putBooleanIfAbsent(store, written, warning, NotificationPreferenceKeys.LOW_BATTERY, notifyLowBattery)
}

private suspend fun BackupUserDefaults.restoreAbsentStrings(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
) {
    putStringIfAbsent(store, written, warning, PersistenceKeys.MAP_STYLE_SELECTION, mapStyleSelection)
    putStringIfAbsent(store, written, warning, PersistenceKeys.MAP_FILTER_MAIN_MAP, mapFilterMainMap)
    putStringIfAbsent(store, written, warning, PersistenceKeys.MAP_FILTER_TRACE_PATH, mapFilterTracePath)
    putStringIfAbsent(store, written, warning, PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR, mapFilterNeighborSNR)
    putStringIfAbsent(store, written, warning, PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE, mapColorSchemePreference)
    putStringIfAbsent(store, written, warning, PersistenceKeys.SELECTED_THEME_ID, selectedThemeID)
    putStringIfAbsent(store, written, warning, PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE, appColorSchemePreference)
    putStringIfAbsent(store, written, warning, NodeSortOrder.SORT_ORDER_PREFERENCE_KEY, nodesSortOrder)
    putStringIfAbsent(store, written, warning, NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY, discoverySortOrder)
    putStringIfAbsent(
        store,
        written,
        warning,
        RecentEmojisStore.PREFS_KEY,
        recentEmojis?.let(RecentEmojisStore::encode),
    )
    putStringIfAbsent(
        store,
        written,
        warning,
        RecentEmojisStore.FREQUENT_PREFS_KEY,
        frequentEmojis?.let(RecentEmojisStore::encode),
    )
    putStringIfAbsent(store, written, warning, PersistenceKeys.TRACE_PATH_VIEW_MODE, tracePathViewMode)
    putStringIfAbsent(store, written, warning, PersistenceKeys.TRANSLATION_TARGET_LANGUAGE, translationTargetLanguage)
}

private suspend fun BackupUserDefaults.restoreAbsentRegion(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
) {
    // Written through RegionSelectionStore so the blob uses the same encoder that reads it.
    val region = regionSelection ?: return
    // Keep later keys. A region persist fault must not abort restore.
    runPreferences(warning, PREFERENCES_RESTORE_FAILED, fallback = {}) {
        if (store.getString(PersistenceKeys.REGION_SELECTION) == null) {
            RegionSelectionStore.save(store, region)
            written += PersistenceKeys.REGION_SELECTION
        }
    }
}

private suspend fun BackupUserDefaults.restoreAbsentStaleDays(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
) = store.recordPreferenceIfAbsent(
    written = written,
    warning = warning,
    key = PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS,
    value = autoDeleteStaleNodesDays,
    access = AbsentPreference(
        read = { getInt(it) },
        write = { preferenceKey, stored -> putInt(preferenceKey, stored) },
    ),
)

// A missing envelope value is not a write. A key already stored is a choice and stays. A DataStore
// fault skips that key; cancel still leaves the restore. iOS UserDefaults.set does not throw, so a
// DataStore fault must not abort the rest of restore.
private class AbsentPreference<T>(
    val read: suspend PreferencesStore.(String) -> T?,
    val write: suspend PreferencesStore.(String, T) -> Unit,
)

private suspend fun <T> PreferencesStore.recordPreferenceIfAbsent(
    written: MutableList<String>,
    warning: (String) -> Unit,
    key: String,
    value: T?,
    access: AbsentPreference<T>,
) {
    if (value == null) return
    val store = this
    runPreferences(warning, PREFERENCES_RESTORE_FAILED, fallback = {}) {
        if (access.read(store, key) != null) return@runPreferences
        access.write(store, key, value)
        written += key
    }
}

private suspend fun putBooleanIfAbsent(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
    key: String,
    value: Boolean?,
) = store.recordPreferenceIfAbsent(
    written = written,
    warning = warning,
    key = key,
    value = value,
    access = AbsentPreference(
        read = { getBoolean(it) },
        write = { preferenceKey, stored -> putBoolean(preferenceKey, stored) },
    ),
)

private suspend fun putStringIfAbsent(
    store: PreferencesStore,
    written: MutableList<String>,
    warning: (String) -> Unit,
    key: String,
    value: String?,
) = store.recordPreferenceIfAbsent(
    written = written,
    warning = warning,
    key = key,
    value = value,
    access = AbsentPreference(
        read = { getString(it) },
        write = { preferenceKey, stored -> putString(preferenceKey, stored) },
    ),
)

private const val PREFERENCES_RESTORE_FAILED = "Preferences restore failed"
