import CoreGraphics
import Foundation

enum IncomingBubbleAvatarMetrics {
  static let size: CGFloat = 28
  static let gap: CGFloat = 6
  static let flightDuration: TimeInterval = 0.25
  static var columnWidth: CGFloat {
    size + gap
  }
}
