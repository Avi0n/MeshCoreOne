import Foundation

/// Typed wrapper for `@AppStorage` / `UserDefaults` key strings: user
/// preferences and lightweight UI state in `UserDefaults.standard`.
/// Connection-infrastructure keys (reverse-DNS `com.pocketmesh.*`) and the
/// theme keys live in `PersistenceKeys`; the two namespaces never share a key.
///
/// Raw values are pinned to the exact on-disk key each callsite previously
/// used as a literal, so a case rename can't silently mint a new key and
/// orphan existing user data. Keep them pinned when adding cases.
///
/// A key added here does not survive backup/restore automatically: register
/// it in `BackupUserDefaults` (a mapping row or a special-cased branch)
/// unless the value is intentionally device-local.
public enum AppStorageKey: String {
    // swiftlint:disable redundant_string_enum_value
    case hasCompletedOnboarding = "hasCompletedOnboarding"
    case liveActivityEnabled = "liveActivityEnabled"
    case showIncomingPath = "showIncomingPath"
    case showIncomingHopCount = "showIncomingHopCount"
    case showIncomingRegion = "showIncomingRegion"
    case showIncomingSendTime = "showIncomingSendTime"
    case linkPreviewsEnabled = "linkPreviewsEnabled"
    case linkPreviewsAutoResolveDM = "linkPreviewsAutoResolveDM"
    case linkPreviewsAutoResolveChannels = "linkPreviewsAutoResolveChannels"
    case showInlineImages = "showInlineImages"
    case autoPlayGIFs = "autoPlayGIFs"
    case replyWithQuote = "replyWithQuote"
    case showMapPreviewThumbnails = "showMapPreviewThumbnails"
    case nodesSortOrder = "nodesSortOrder"
    case discoverySortOrder = "discoverySortOrder"
    case tracePathViewMode = "tracePathViewMode"
    case mapStyleSelection = "mapStyleSelection"
    case mapShowLabels = "mapShowLabels"
    case hasSeenRepeaterDragHint = "hasSeenRepeaterDragHint"
    case autoDeleteStaleNodesDays = "autoDeleteStaleNodesDays"
    case lastStaleCleanupDate = "lastStaleCleanupDate"
    case frequentEmojis = "frequentEmojis"
    case recentReactionEmojis = "recentReactionEmojis"
    case isDemoModeUnlocked = "isDemoModeUnlocked"
    case isDemoModeEnabled = "isDemoModeEnabled"

    // Notification toggles read by NotificationPreferences and
    // NotificationPreferencesStore; all share defaultNotificationEnabled.
    case notifyContactMessages = "notifyContactMessages"
    case notifyChannelMessages = "notifyChannelMessages"
    case notifyRoomMessages = "notifyRoomMessages"
    case notifyNewContacts = "notifyNewContacts"
    case notifyNewContactsContact = "notifyNewContactsContact"
    case notifyNewContactsRepeater = "notifyNewContactsRepeater"
    case notifyNewContactsRoom = "notifyNewContactsRoom"
    case notifyReactions = "notifyReactions"
    case notificationSoundEnabled = "notificationSoundEnabled"
    case notificationBadgeEnabled = "notificationBadgeEnabled"
    case notifyLowBattery = "notifyLowBattery"
    // swiftlint:enable redundant_string_enum_value

    public static let defaultShowIncomingPath: Bool = false
    public static let defaultShowIncomingHopCount: Bool = false
    public static let defaultShowIncomingRegion: Bool = false
    public static let defaultShowIncomingSendTime: Bool = false
    public static let defaultLinkPreviewsEnabled: Bool = false
    public static let defaultLinkPreviewsAutoResolveDM: Bool = true
    public static let defaultLinkPreviewsAutoResolveChannels: Bool = true
    public static let defaultShowInlineImages: Bool = true
    public static let defaultAutoPlayGIFs: Bool = true
    public static let defaultReplyWithQuote: Bool = false
    public static let defaultShowMapPreviewThumbnails: Bool = true
    public static let defaultMapShowLabels: Bool = true
    public static let defaultHasSeenRepeaterDragHint: Bool = false
    public static let defaultLiveActivityEnabled: Bool = true
    /// Days before a non-favorite node is auto-deleted; 0 disables cleanup.
    public static let defaultAutoDeleteStaleNodesDays: Int = 0
    /// `timeIntervalSinceReferenceDate` of the last cleanup; 0 means never ran.
    public static let defaultLastStaleCleanupDate: Double = 0
    /// Shared default for every notification toggle case.
    public static let defaultNotificationEnabled: Bool = true
}
