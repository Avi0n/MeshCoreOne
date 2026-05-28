import Foundation

/// Typed wrapper for `@AppStorage` / `UserDefaults` key strings.
///
/// String raw values default to the case name, so each `rawValue` matches
/// the on-disk key that callsites previously used as a literal. Keep that
/// invariant when adding cases, or backup/restore will silently orphan
/// existing user data on upgrade.
///
/// Currently scoped to the region-related keys that previously appeared
/// at multiple `@AppStorage` callsites (`MessagesSettingsSection` and
/// `ChatConversationView`) plus the matching `BackupUserDefaults`
/// `boolMappings` entries. New keys land here as the surrounding code
/// is touched; a wider sweep is intentionally deferred.
public enum AppStorageKey: String {
    case showIncomingPath
    case showIncomingHopCount
    case showIncomingRegion
    case linkPreviewsEnabled
    case showInlineImages
    case autoPlayGIFs
    case replyWithQuote
    case showMapPreviewThumbnails

    public static let defaultShowIncomingPath: Bool = false
    public static let defaultShowIncomingHopCount: Bool = false
    public static let defaultShowIncomingRegion: Bool = false
    public static let defaultLinkPreviewsEnabled: Bool = false
    public static let defaultShowInlineImages: Bool = true
    public static let defaultAutoPlayGIFs: Bool = true
    public static let defaultReplyWithQuote: Bool = false
    public static let defaultShowMapPreviewThumbnails: Bool = true
}
