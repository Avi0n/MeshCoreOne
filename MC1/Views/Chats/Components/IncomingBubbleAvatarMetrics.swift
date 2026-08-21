import CoreGraphics

enum IncomingBubbleAvatarMetrics {
  static let size: CGFloat = 28
  static let gap: CGFloat = 6
  static var columnWidth: CGFloat {
    size + gap
  }
}
