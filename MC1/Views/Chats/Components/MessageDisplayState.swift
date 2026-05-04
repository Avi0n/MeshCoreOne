import SwiftUI
import UIKit
import MC1Services

/// Display state for a message bubble (typically derived from MessageDisplayItem)
struct MessageDisplayState {
    var showTimestamp: Bool = false
    var showDirectionGap: Bool = false
    var showSenderName: Bool = true
    var showNewMessagesDivider: Bool = false
    var detectedURL: URL?
    var previewState: PreviewLoadState = .idle
    var loadedPreview: LinkPreviewDataDTO?
    var isImageURL: Bool = false
    var decodedImage: UIImage?
    var decodedPreviewImage: UIImage?
    var decodedPreviewIcon: UIImage?
    var isGIF: Bool = false
    var showInlineImages: Bool = false
    var autoPlayGIFs: Bool = true
    var showIncomingHopCount: Bool = false
    var showIncomingRegion: Bool = false
    var formattedPath: String?
    var formattedText: AttributedString?
}
