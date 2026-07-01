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
  case hasCompletedOnboarding
  case liveActivityEnabled
  case showIncomingPath
  case showIncomingHopCount
  case showIncomingRegion
  case showIncomingSendTime
  case linkPreviewsEnabled
  case linkPreviewsAutoResolveDM
  case linkPreviewsAutoResolveChannels
  case showInlineImages
  case autoPlayGIFs
  case replyWithQuote
  case showMapPreviewThumbnails
  case nodesSortOrder
  case discoverySortOrder
  case tracePathViewMode
  case mapStyleSelection
  case mapShowLabels
  case hasSeenRepeaterDragHint
  case autoDeleteStaleNodesDays
  case lastStaleCleanupDate
  case frequentEmojis
  case recentReactionEmojis
  case isDemoModeUnlocked
  case isDemoModeEnabled
  /// Intentionally device-local: not registered in BackupUserDefaults so restored
  /// hardware still shows the current release's notes once. Registering it would
  /// suppress the sheet on a genuinely new device.
  case lastShownWhatsNewVersion

  // Notification toggles read by NotificationPreferences and
  // NotificationPreferencesStore; all share defaultNotificationEnabled.
  case notifyContactMessages
  case notifyChannelMessages
  case notifyRoomMessages
  case notifyNewContacts
  case notifyNewContactsContact
  case notifyNewContactsRepeater
  case notifyNewContactsRoom
  case notifyReactions
  case notificationSoundEnabled
  case notificationBadgeEnabled
  case notifyLowBattery

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
