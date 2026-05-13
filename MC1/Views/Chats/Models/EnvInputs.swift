import Foundation
import MC1Services

/// Environment-derived inputs that influence `MessageItem` content. Sourced
/// from `@AppStorage` (six toggles), `@Environment(\.colorSchemeContrast)`,
/// and the parent view's `deviceName`. `ChatConversationView` constructs one
/// and pushes it to `ChatViewModel.applyEnvInputs(_:)`; the view model
/// rebuilds `MessageItem`s when the value changes.
struct EnvInputs: Sendable, Hashable {
    let showInlineImages: Bool
    let autoPlayGIFs: Bool
    let showIncomingPath: Bool
    let showIncomingHopCount: Bool
    let showIncomingRegion: Bool
    let previewsEnabled: Bool
    let isHighContrast: Bool
    let currentUserName: String

    static let `default` = EnvInputs(
        showInlineImages: AppStorageKey.defaultShowInlineImages,
        autoPlayGIFs: AppStorageKey.defaultAutoPlayGIFs,
        showIncomingPath: AppStorageKey.defaultShowIncomingPath,
        showIncomingHopCount: AppStorageKey.defaultShowIncomingHopCount,
        showIncomingRegion: AppStorageKey.defaultShowIncomingRegion,
        previewsEnabled: AppStorageKey.defaultLinkPreviewsEnabled,
        isHighContrast: false,
        currentUserName: ""
    )
}
