import Foundation

/// Environment-derived inputs that influence `MessageItem` content. Sourced
/// from `@AppStorage` (six toggles), `@Environment(\.colorSchemeContrast)`,
/// and the parent view's `deviceName`. `ChatConversationView` constructs one
/// and pushes it to `ChatViewModel.applyEnvInputs(_:)`; the view model
/// rebuilds `MessageItem`s when the value changes.
public struct EnvInputs: Sendable, Hashable {
    public let showInlineImages: Bool
    public let autoPlayGIFs: Bool
    public let showIncomingPath: Bool
    public let showIncomingHopCount: Bool
    public let showIncomingRegion: Bool
    public let previewsEnabled: Bool
    public let isHighContrast: Bool
    /// Light/dark appearance, sourced from `@Environment(\.colorScheme)` in
    /// `ChatConversationView`. Threaded into `MapPreviewFragmentState` so the map
    /// thumbnail renders against the matching style. Like `isHighContrast`, a
    /// change forces a full `buildItems()` rebuild (rare, OS-driven).
    public let isDark: Bool
    public let currentUserName: String

    public init(
        showInlineImages: Bool,
        autoPlayGIFs: Bool,
        showIncomingPath: Bool,
        showIncomingHopCount: Bool,
        showIncomingRegion: Bool,
        previewsEnabled: Bool,
        isHighContrast: Bool,
        isDark: Bool,
        currentUserName: String
    ) {
        self.showInlineImages = showInlineImages
        self.autoPlayGIFs = autoPlayGIFs
        self.showIncomingPath = showIncomingPath
        self.showIncomingHopCount = showIncomingHopCount
        self.showIncomingRegion = showIncomingRegion
        self.previewsEnabled = previewsEnabled
        self.isHighContrast = isHighContrast
        self.isDark = isDark
        self.currentUserName = currentUserName
    }

    public static let `default` = EnvInputs(
        showInlineImages: AppStorageKey.defaultShowInlineImages,
        autoPlayGIFs: AppStorageKey.defaultAutoPlayGIFs,
        showIncomingPath: AppStorageKey.defaultShowIncomingPath,
        showIncomingHopCount: AppStorageKey.defaultShowIncomingHopCount,
        showIncomingRegion: AppStorageKey.defaultShowIncomingRegion,
        previewsEnabled: AppStorageKey.defaultLinkPreviewsEnabled,
        isHighContrast: false,
        isDark: false,
        currentUserName: ""
    )
}
