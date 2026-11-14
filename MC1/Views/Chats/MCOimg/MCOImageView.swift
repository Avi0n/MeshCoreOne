import MC1Services
import SwiftUI

struct MCOImageView: View {
  let content: MCOImageContent
  private static let maximumSide: CGFloat = 220

  var body: some View {
    switch content {
    case .raster, .document:
      MCOImageRenderer(content: content)
        .frame(width: displaySize.width, height: displaySize.height)
        .accessibilityLabel(L10n.Chats.Chats.Mcoimg.image)
    case .unsupported:
      Label(L10n.Chats.Chats.Mcoimg.unsupported, systemImage: "photo.badge.exclamationmark")
    case .damaged:
      Label(L10n.Chats.Chats.Mcoimg.damaged, systemImage: "photo.badge.exclamationmark")
    }
  }

  private var displaySize: CGSize {
    let width: Int
    let height: Int
    switch content {
    case let .raster(image): (width, height) = (image.width, image.height)
    case let .document(document): (width, height) = (document.width, document.height)
    case .unsupported, .damaged: return .zero
    }
    let scale = Self.maximumSide / CGFloat(max(width, height))
    return CGSize(width: CGFloat(width) * scale, height: CGFloat(height) * scale)
  }
}

extension MCOImageContent {
  var accessibilityLabel: String {
    switch self {
    case .raster, .document: L10n.Chats.Chats.Mcoimg.image
    case .unsupported: L10n.Chats.Chats.Mcoimg.unsupported
    case .damaged: L10n.Chats.Chats.Mcoimg.damaged
    }
  }
}
