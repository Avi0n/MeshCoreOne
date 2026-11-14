import Foundation
import MC1Services

/// `.text`, `.inlineImage`, and `.mcoImage` render in `BubbleFragmentStack`; every other kind is a sibling in `UnifiedMessageBubble`.
/// Computed from `item.content` and excluded from `MessageItem` equality.
struct FragmentLayout {
  /// The single text fragment that fills the bubble box, if any.
  let textPayload: MessageTextPayload?

  /// The single inline image attached to the bubble box, if any.
  let inlineImage: InlineImage?

  /// The single MCOimg attached to the bubble box, if any.
  let mcoImage: MCOImageContent?

  /// Fragments below the box, in document order, excluding box-resident text, the inline image, and MCOimg.
  let siblings: [MessageFragment]

  init(content: [MessageFragment]) {
    var textPayload: MessageTextPayload?
    var inlineImage: InlineImage?
    var mcoImage: MCOImageContent?
    var siblings: [MessageFragment] = []

    for fragment in content {
      switch fragment {
      case let .text(payload):
        if textPayload == nil { textPayload = payload }
      case let .inlineImage(image):
        if inlineImage == nil { inlineImage = image }
      case let .mcoImage(image):
        if mcoImage == nil { mcoImage = image }
      case .linkPreview, .mapPreview, .malwareWarning, .reactionSummary:
        siblings.append(fragment)
      }
    }

    self.textPayload = textPayload
    self.inlineImage = inlineImage
    self.mcoImage = mcoImage
    self.siblings = siblings
  }
}
