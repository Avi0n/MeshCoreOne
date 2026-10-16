package one.meshcore.shared.backup

import kotlinx.serialization.Serializable

/**
 * Snapshot of the UserDefaults keys carried in a backup. Mirrors Swift `BackupUserDefaults`: every
 * field is optional and a null value means the key was absent at export time. Property names match
 * the JSON keys, so decode maps by name; unlisted keys are ignored by [backupJson].
 */
@Serializable
data class BackupUserDefaults(
    // App preferences
    val hasCompletedOnboarding: Boolean? = null,
    val liveActivityEnabled: Boolean? = null,
    val mapStyleSelection: String? = null,
    val selectedThemeID: String? = null,
    val appColorSchemePreference: String? = null,
    val mapShowLabels: Boolean? = null,
    val mapClusteringEnabled: Boolean? = null,
    val mapNorthLocked: Boolean? = null,
    val showDiscoveredNodesOnMap: Boolean? = null,
    val mapFilterMainMap: String? = null,
    val mapFilterTracePath: String? = null,
    val mapFilterNeighborSNR: String? = null,
    val mapColorSchemePreference: String? = null,
    val replyWithQuote: Boolean? = null,
    val showInlineImages: Boolean? = null,
    val autoPlayGIFs: Boolean? = null,
    val showIncomingPath: Boolean? = null,
    val showIncomingHopCount: Boolean? = null,
    val showIncomingRegion: Boolean? = null,
    val showIncomingHeardCount: Boolean? = null,
    val showIncomingSendTime: Boolean? = null,
    val autoDeleteStaleNodesDays: Int? = null,
    val discoverySortOrder: String? = null,
    val nodesSortOrder: String? = null,
    val tracePathViewMode: String? = null,
    val linkPreviewsEnabled: Boolean? = null,
    val linkPreviewsAutoResolveDM: Boolean? = null,
    val linkPreviewsAutoResolveChannels: Boolean? = null,
    val showMapPreviewThumbnails: Boolean? = null,
    val frequentEmojis: List<String>? = null,
    val recentEmojis: List<String>? = null,
    val hasSeenRepeaterDragHint: Boolean? = null,
    val translationTargetLanguage: String? = null,
    val translationOffersEnabled: Boolean? = null,
    val useDefaultTranslationApp: Boolean? = null,
    val regionSelection: RegionSelection? = null,
    // Notification preferences
    val notifyContactMessages: Boolean? = null,
    val notifyChannelMessages: Boolean? = null,
    val notifyRoomMessages: Boolean? = null,
    val notifyNewContacts: Boolean? = null,
    val notifyNewContactsContact: Boolean? = null,
    val notifyNewContactsRepeater: Boolean? = null,
    val notifyNewContactsRoom: Boolean? = null,
    val notifyReactions: Boolean? = null,
    val notificationSoundEnabled: Boolean? = null,
    val notificationBadgeEnabled: Boolean? = null,
    val notifyLowBattery: Boolean? = null,
)
