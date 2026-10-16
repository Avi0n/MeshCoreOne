import CoreGraphics

/// Tuning constants for the chat scroll surface.
enum ChatScrollConstants {
  /// Distance from the bottom (in points) at or below which the list is treated
  /// as resting at the visual bottom, hiding the scroll-to-bottom button and
  /// re-enabling auto-scroll on append.
  static let bottomDetectionThreshold: CGFloat = 40

  /// Hosted tests find the overlay button by identifier. iOS 26 Liquid Glass
  /// does not put the VoiceOver label on `UIView.accessibilityLabel` without
  /// VoiceOver running.
  static let scrollToBottomButtonIdentifier = "chat.scrollToBottom"
}
