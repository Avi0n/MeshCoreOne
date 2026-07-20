import CoreGraphics

/// Tuning constants for the chat scroll surface.
enum ChatScrollConstants {
  /// Distance from the bottom (in points) at or below which the list is treated
  /// as resting at the visual bottom, hiding the scroll-to-bottom button and
  /// re-enabling auto-scroll on append.
  static let bottomDetectionThreshold: CGFloat = 40
}
