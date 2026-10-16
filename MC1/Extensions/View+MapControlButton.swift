import SwiftUI

extension View {
  /// Applies the shared styling for a map control toolbar button: a fixed
  /// 44pt icon-only tappable square with a medium-weight glyph and the given tint.
  func mapControlButton(tint: Color) -> some View {
    font(.body.weight(.medium))
      .foregroundStyle(tint)
      .frame(width: MapControlButtonMetrics.size, height: MapControlButtonMetrics.size)
      .contentShape(.rect)
      .buttonStyle(.plain)
      .labelStyle(.iconOnly)
  }
}

private enum MapControlButtonMetrics {
  static let size: CGFloat = 44
}
