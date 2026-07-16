import SwiftUI

/// Geometry for the sender name above an incoming message bubble, shared so a
/// tweak cannot drift `UnifiedMessageBubble` and `RoomMessageBubble` apart.
/// Covers placement only; the two style their names independently.
enum SenderNameMetrics {
  /// Vertical gap between the sender name and the bubble it labels.
  static let bubbleGap: CGFloat = 3

  /// Indent aligning the name past the rounded corner of its bubble. Measured
  /// from the bubble's leading edge, not its text inset.
  static let leadingIndent: CGFloat = 4
}

extension View {
  /// Places a sender name above the bubble it labels: indented past the
  /// bubble's rounded corner and `SenderNameMetrics.bubbleGap` clear of the
  /// bubble. The enclosing stack's spacing already contributes to that gap, so
  /// pass it here to have it counted once rather than twice.
  func senderNamePlacement(enclosingStackSpacing: CGFloat = 0) -> some View {
    padding(.leading, SenderNameMetrics.leadingIndent)
      .padding(.bottom, SenderNameMetrics.bubbleGap - enclosingStackSpacing)
  }
}
