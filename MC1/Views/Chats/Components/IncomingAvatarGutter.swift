import MC1Services
import SwiftUI

struct IncomingAvatarGutter<Content: View>: View {
  let identity: IncomingAvatarIdentity?
  let reserveColumn: Bool
  @ViewBuilder var content: Content

  var body: some View {
    if let identity {
      HStack(alignment: .bottom, spacing: IncomingBubbleAvatarMetrics.gap) {
        ContactAvatar(
          name: identity.name,
          size: IncomingBubbleAvatarMetrics.size,
          imageData: IncomingAvatarJPEGStore.data(for: identity.matchedContactID)
        )
        .accessibilityHidden(true)
        content
      }
      .contentShape(.rect)
    } else if reserveColumn {
      content.padding(.leading, IncomingBubbleAvatarMetrics.columnWidth)
    } else {
      content
    }
  }
}
