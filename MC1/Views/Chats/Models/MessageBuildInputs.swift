import Foundation
import SwiftUI
import MC1Services

/// Per-message build inputs. `Sendable`, value-typed snapshot the builder
/// consumes. The view model constructs one per message at build time from its
/// current properties. `imageRefs` carry handles, not UIImages — UIImage
/// resolution happens at render time via the bubble's `imageResolver`
/// callback (UIImage is not Sendable). Env-derived flags live on
/// `EnvInputs`, the builder's second parcel.
struct MessageBuildInputs: Sendable, Hashable {
    let messageID: UUID
    let previewState: PreviewLoadState
    let loadedPreview: LinkPreviewDataDTO?
    let cachedURL: URL?
    let hasInlineImageRef: Bool
    let hasPreviewImageRef: Bool
    let hasPreviewIconRef: Bool
    let imageIsGIF: Bool
    let formattedText: AttributedString?
    let baseColor: Color
    let formattedPath: String?
    let senderResolution: NodeNameResolution
    let showTimestamp: Bool
    let showDirectionGap: Bool
    let showSenderName: Bool
    let showNewMessagesDivider: Bool
}
