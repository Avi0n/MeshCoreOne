package one.meshcore.shared.prefs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Preference key strings are single-sourced so the Android DataStore layer and the
 * iOS `UserDefaults` agree on every name across versions. Values are pinned to the exact strings the iOS
 * app uses on disk (from the Swift `PersistenceKeys` and `AppStorageKey`), so a rename cannot silently
 * mint a new key and orphan existing data.
 *
 * Two namespaces, kept exactly as iOS: connection-infrastructure keys use the reverse-DNS
 * `com.pocketmesh.*` prefix; user-preference keys use the bare case name.
 */
object PersistenceKeys {
    // Connection infrastructure (reverse-DNS), from the Swift PersistenceKeys.
    const val LAST_CONNECTED_DEVICE_ID = "com.pocketmesh.lastConnectedDeviceID"
    const val LAST_CONNECTED_DEVICE_NAME = "com.pocketmesh.lastConnectedDeviceName"

    /**
     * The last connected radio's transport handle (Android-new). On iOS `lastConnectedDeviceID` doubles
     * as the reconnect handle (the CoreBluetooth UUID); on Android the `Device` row id is a
     * non-reversible hash of the Kable peripheral id, so the handle needed to reconnect over BLE is
     * persisted separately. WiFi reconnect uses the device row's `connectionMethods` (host/port) instead.
     */
    const val LAST_CONNECTED_DEVICE_HANDLE = "com.pocketmesh.lastConnectedDeviceHandle"
    const val LAST_CONNECTED_RADIO_ID = "com.pocketmesh.lastConnectedRadioID"
    const val LAST_DISCONNECT_DIAGNOSTIC = "com.pocketmesh.lastDisconnectDiagnostic"

    /**
     * The BLE handle whose bond last completed a verified encrypted session, and when (epoch seconds).
     * The cross-launch analog of the iOS `com.pocketmesh.lastBondVerifiedDeviceID` / `lastBondVerifiedDate`
     * pair: iOS keys the bond slot by the stable `Device` row id, but the Kotlin reconnect grace is keyed
     * by the BLE handle (the reconnect loop targets the handle, not the row id), so the persisted slot
     * stores the handle and an epoch-seconds stamp in the same wall clock as `nowEpochSeconds`.
     *
     * A single slot, deliberately separate from [LAST_CONNECTED_DEVICE_HANDLE]: a WiFi connection
     * overwrites the connection handle without carrying a bond, so the shield must not be clobbered by it.
     */
    const val LAST_BOND_VERIFIED_HANDLE = "com.pocketmesh.lastBondVerifiedHandle"
    const val LAST_BOND_VERIFIED_EPOCH_SECONDS = "com.pocketmesh.lastBondVerifiedEpochSeconds"

    /** Set only when the user explicitly disconnects; the persisted bit of `ConnectionIntent`. */
    const val USER_EXPLICITLY_DISCONNECTED = "com.pocketmesh.userExplicitlyDisconnected"

    // User preferences (bare case name), from the Swift AppStorageKey.
    const val HAS_COMPLETED_ONBOARDING = "hasCompletedOnboarding"
    const val AUTO_DELETE_STALE_NODES_DAYS = "autoDeleteStaleNodesDays"
    const val LAST_STALE_CLEANUP_DATE = "lastStaleCleanupDate"
    const val IS_DEMO_MODE_UNLOCKED = "isDemoModeUnlocked"
    const val IS_DEMO_MODE_ENABLED = "isDemoModeEnabled"
    const val IS_SCREENSHOT_MODE_ENABLED = "isScreenshotModeEnabled"

    // Message display (bare case names from AppStorageKey).
    const val SHOW_INCOMING_PATH = "showIncomingPath"
    const val SHOW_INCOMING_HOP_COUNT = "showIncomingHopCount"
    const val SHOW_INCOMING_REGION = "showIncomingRegion"
    const val SHOW_INCOMING_HEARD_COUNT = "showIncomingHeardCount"
    const val SHOW_INCOMING_SEND_TIME = "showIncomingSendTime"
    const val REPLY_WITH_QUOTE = "replyWithQuote"
    const val LINK_PREVIEWS_ENABLED = "linkPreviewsEnabled"

    /** Auto-play animated GIFs in chat; from Swift `AppStorageKey.autoPlayGIFs`. */
    const val AUTO_PLAY_GIFS = "autoPlayGIFs"

    /** Last six reaction emojis; from Swift `AppStorageKey.recentReactionEmojis`. */
    const val RECENT_REACTION_EMOJIS = "recentReactionEmojis"

    /** Last 20 emoji-picker uses; from Swift `AppStorageKey.frequentEmojis`. */
    const val FREQUENT_EMOJIS = "frequentEmojis"

    /** Tools → Trace Path list/map toggle; from Swift `AppStorageKey.tracePathViewMode`. */
    const val TRACE_PATH_VIEW_MODE = "tracePathViewMode"
    const val TRACE_PATH_VIEW_MODE_LIST = "list"
    const val TRACE_PATH_VIEW_MODE_MAP = "map"

    /** One-time Line of Sight repeater-drag coach; from Swift `AppStorageKey.hasSeenRepeaterDragHint`. */
    const val HAS_SEEN_REPEATER_DRAG_HINT = "hasSeenRepeaterDragHint"

    /** In-bubble Translate target; from Swift `AppStorageKey.translationTargetLanguage`. */
    const val TRANSLATION_TARGET_LANGUAGE = "translationTargetLanguage"
    const val DEFAULT_TRANSLATION_TARGET_LANGUAGE = "app"
    const val TRANSLATION_OFFERS_ENABLED = "translationOffersEnabled"
    const val DEFAULT_TRANSLATION_OFFERS_ENABLED = true

    /** Tap-Translate opens the system Translate sheet; from Swift `AppStorageKey.useDefaultTranslationApp`. */
    const val USE_DEFAULT_TRANSLATION_APP = "useDefaultTranslationApp"
    const val DEFAULT_USE_DEFAULT_TRANSLATION_APP = false

    /** Map thumbnail previews in chat; from Swift `AppStorageKey.showMapPreviewThumbnails`. */
    const val SHOW_MAP_PREVIEW_THUMBNAILS = "showMapPreviewThumbnails"

    // Defaults match AppStorageKey.default* (message metadata off until the user opts in;
    // map previews default on, matching iOS defaultShowMapPreviewThumbnails).
    const val DEFAULT_SHOW_INCOMING_PATH = false
    const val DEFAULT_SHOW_INCOMING_HOP_COUNT = false
    const val DEFAULT_SHOW_INCOMING_REGION = false
    const val DEFAULT_SHOW_INCOMING_HEARD_COUNT = false
    const val DEFAULT_SHOW_INCOMING_SEND_TIME = false
    const val DEFAULT_REPLY_WITH_QUOTE = false
    const val DEFAULT_LINK_PREVIEWS_ENABLED = false

    /** GIF autoplay defaults on, matching iOS `defaultAutoPlayGIFs`. */
    const val DEFAULT_AUTO_PLAY_GIFS = true
    const val DEFAULT_SHOW_MAP_PREVIEW_THUMBNAILS = true

    /** The region-selection JSON blob key, from the Swift `BackupUserDefaults.regionSelectionKey`. */
    const val REGION_SELECTION = "userPrefs.region"

    /** Selected theme id (bare string); from Swift `PersistenceKeys.selectedThemeID`. */
    const val SELECTED_THEME_ID = "selectedThemeID"

    /** Light/dark/system preference raw; from Swift `PersistenceKeys.appColorSchemePreference`. */
    const val APP_COLOR_SCHEME_PREFERENCE = "appColorSchemePreference"

    /**
     * Last map camera region (`lat,lon,latDelta,lonDelta` via [one.meshcore.shared.map.MapCameraStore]).
     * Pinned to Swift `SceneStorageKey.mapCameraRegion` so the encoding is shared; Android persists in
     * DataStore rather than scene restoration.
     */
    const val MAP_CAMERA_REGION = "mapCameraRegion"

    /** Selected map style raw (`standard` / `satellite` / `topo`); from Swift `AppStorageKey.mapStyleSelection`. */
    const val MAP_STYLE_SELECTION = "mapStyleSelection"

    /** Per-host map filter JSON; from Swift `AppStorageKey.mapFilterMainMap`. */
    const val MAP_FILTER_MAIN_MAP = "mapFilterMainMap"

    /** Trace-path map filter JSON; from Swift `AppStorageKey.mapFilterTracePath`. */
    const val MAP_FILTER_TRACE_PATH = "mapFilterTracePath"

    /** Neighbor-SNR map filter JSON; from Swift `AppStorageKey.mapFilterNeighborSNR`. */
    const val MAP_FILTER_NEIGHBOR_SNR = "mapFilterNeighborSNR"

    /** Union passively-discovered nodes onto the map; from Swift `AppStorageKey.showDiscoveredNodesOnMap`. */
    const val SHOW_DISCOVERED_NODES_ON_MAP = "showDiscoveredNodesOnMap"

    /** Draw on-map name labels; from Swift `AppStorageKey.mapShowLabels`. */
    const val MAP_SHOW_LABELS = "mapShowLabels"

    /** Labels start visible, matching Swift `defaultMapShowLabels`. */
    const val DEFAULT_MAP_SHOW_LABELS = true

    /** Merge nearby node pins into count bubbles; from Swift `AppStorageKey.mapClusteringEnabled`. */
    const val MAP_CLUSTERING_ENABLED = "mapClusteringEnabled"

    /** Clustering starts on, matching Swift `defaultMapClusteringEnabled`. */
    const val DEFAULT_MAP_CLUSTERING_ENABLED = true

    /** Lock the map heading to north; from Swift `AppStorageKey.mapNorthLocked`. Same key so backup shares it. */
    const val MAP_NORTH_LOCKED = "mapNorthLocked"

    /** North lock starts off, matching Swift `defaultMapNorthLocked`. */
    const val DEFAULT_MAP_NORTH_LOCKED = false

    /**
     * Basemap light/dark/system preference raw; from Swift `AppStorageKey.mapColorSchemePreference`.
     * Independent of the app-chrome [APP_COLOR_SCHEME_PREFERENCE]: it styles map tiles only.
     */
    const val MAP_COLOR_SCHEME_PREFERENCE = "mapColorSchemePreference"

    /** Discovered nodes stay off the map until the user opts in; from Swift `defaultShowDiscoveredNodesOnMap`. */
    const val DEFAULT_SHOW_DISCOVERED_NODES_ON_MAP = false

    const val DEFAULT_THEME_ID = "default"
    const val DEFAULT_COLOR_SCHEME_PREFERENCE = "system"

    /**
     * Minimum interval between stale-node cleanup runs. The code enforces 3 hours; the iOS doc-comment
     * says 6h but the enforced value is 3h, and the enforced value is what is mirrored here.
     */
    val STALE_CLEANUP_COOLDOWN: Duration = 3.hours
}
