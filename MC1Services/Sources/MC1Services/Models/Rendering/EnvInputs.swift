import Foundation

/// Environment-derived inputs that influence `MessageItem` content. Sourced
/// from `@AppStorage` (seven toggles), `@Environment(\.colorSchemeContrast)`,
/// and the parent view's `deviceName`. `ChatConversationView` constructs one
/// and pushes it to `ChatViewModel.applyEnvInputs(_:)`; the view model
/// rebuilds `MessageItem`s when the value changes.
public struct EnvInputs: Sendable, Hashable {
  public let showInlineImages: Bool
  public let autoPlayGIFs: Bool
  public let showIncomingPath: Bool
  public let showIncomingHopCount: Bool
  public let showIncomingRegion: Bool
  public let showIncomingSendTime: Bool
  public let previewsEnabled: Bool
  public let isHighContrast: Bool
  /// Light/dark appearance, sourced from `@Environment(\.colorScheme)` in
  /// `ChatConversationView`. Threaded into `MapPreviewFragmentState` so the map
  /// thumbnail renders against the matching style. Like `isHighContrast`, a
  /// change forces a full `buildItems()` rebuild (rare, OS-driven).
  public let isDark: Bool
  /// User-controlled privacy gate. When false, `MessageFragmentBuilder` skips
  /// the map-preview fragment entirely so `MapPreviewFragmentView.onAppear`
  /// never fires the third-party tile request — the coordinate text in the
  /// message body remains tappable.
  public let showMapPreviews: Bool
  /// True when `OfflineMapService.isNetworkAvailable` is false. Sourced in
  /// `ChatConversationView` and threaded through `MapSnapshotRequest` so the
  /// snapshotter routes to the offline-pack style URL and the cache key for
  /// online and offline renders does not collide.
  public let isOffline: Bool
  public let currentUserName: String
  /// Active theme identifier (`Theme.id`). A `Sendable, Hashable` token — never a SwiftUI
  /// `Color`, which would pull SwiftUI into MC1Services and break `Hashable`. The MC1 side
  /// resolves it back to a `Theme` to bake outgoing-text/hashtag colors into `MessageTextPayload`.
  public let themeID: String

  /// Dynamic Type size fingerprint. A `Sendable, Hashable` token (a `DynamicTypeSize` case
  /// name string supplied by the MC1 side, never the SwiftUI type itself) so a Dynamic Type
  /// change bumps the `EnvInputs` equality fingerprint like `themeID` and `isHighContrast` do,
  /// forcing a full `buildItems()` rebuild. It does not key the bubble text view's size cache:
  /// that cache is keyed on the `dynamicTypeSize` the renderer reads directly from its own
  /// `@Environment`. Reflow of already-visible cells is driven by the appearance reconfigure path.
  public let contentSizeCategory: String

  public init(
    showInlineImages: Bool,
    autoPlayGIFs: Bool,
    showIncomingPath: Bool,
    showIncomingHopCount: Bool,
    showIncomingRegion: Bool,
    showIncomingSendTime: Bool,
    previewsEnabled: Bool,
    isHighContrast: Bool,
    isDark: Bool,
    showMapPreviews: Bool,
    isOffline: Bool,
    currentUserName: String,
    themeID: String,
    contentSizeCategory: String
  ) {
    self.showInlineImages = showInlineImages
    self.autoPlayGIFs = autoPlayGIFs
    self.showIncomingPath = showIncomingPath
    self.showIncomingHopCount = showIncomingHopCount
    self.showIncomingRegion = showIncomingRegion
    self.showIncomingSendTime = showIncomingSendTime
    self.previewsEnabled = previewsEnabled
    self.isHighContrast = isHighContrast
    self.isDark = isDark
    self.showMapPreviews = showMapPreviews
    self.isOffline = isOffline
    self.currentUserName = currentUserName
    self.themeID = themeID
    self.contentSizeCategory = contentSizeCategory
  }

  /// Identifier of the built-in default theme. Shared so `EnvInputs.default` and `Theme.default.id`
  /// (defined in the MC1 layer, which cannot see `Theme` from here) cannot drift apart.
  public static let defaultThemeID = "default"

  /// The system's unscaled Dynamic Type baseline (`DynamicTypeSize.large`). Shared so
  /// `EnvInputs.default` and the MC1-side token mapper agree on the baseline string and
  /// cannot drift apart.
  public static let defaultContentSizeCategory = "large"

  public static let `default` = EnvInputs(
    showInlineImages: AppStorageKey.defaultShowInlineImages,
    autoPlayGIFs: AppStorageKey.defaultAutoPlayGIFs,
    showIncomingPath: AppStorageKey.defaultShowIncomingPath,
    showIncomingHopCount: AppStorageKey.defaultShowIncomingHopCount,
    showIncomingRegion: AppStorageKey.defaultShowIncomingRegion,
    showIncomingSendTime: AppStorageKey.defaultShowIncomingSendTime,
    previewsEnabled: AppStorageKey.defaultLinkPreviewsEnabled,
    isHighContrast: false,
    isDark: false,
    showMapPreviews: AppStorageKey.defaultShowMapPreviewThumbnails,
    isOffline: false,
    currentUserName: "",
    themeID: defaultThemeID,
    contentSizeCategory: defaultContentSizeCategory
  )
}
