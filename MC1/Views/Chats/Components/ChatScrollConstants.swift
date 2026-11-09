import CoreGraphics
import Foundation

/// Tuning constants for the chat scroll surface.
enum ChatScrollConstants {
  /// Distance from the bottom, in points, at or below which the list counts as resting there.
  /// That hides the scroll-to-bottom button and turns append-follow back on.
  static let bottomDetectionThreshold: CGFloat = 40

  /// Hosted tests find the overlay button by this identifier.
  /// Liquid Glass does not expose its VoiceOver label until VoiceOver is running.
  static let scrollToBottomButtonIdentifier = "chat.scrollToBottom"

  static func messageAccessibilityIdentifier(_ id: UUID) -> String {
    "chat.message.\(id.uuidString)"
  }
}
