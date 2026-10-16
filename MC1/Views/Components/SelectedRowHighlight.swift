import SwiftUI

/// Rounded accent fill marking the selected row in a manually-driven list (a tap `Button` rather
/// than native `List(selection:)`). Shared by the conversation and node split lists.
struct SelectedRowHighlight: ViewModifier {
  let isSelected: Bool

  @Environment(\.appTheme) private var theme

  private static let cornerRadius: CGFloat = 10
  private static let fillOpacity: Double = 0.18
  private static let insetVertical: CGFloat = 2
  private static let insetHorizontal: CGFloat = 8

  func body(content: Content) -> some View {
    content.background {
      if isSelected {
        RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
          .fill(theme.accentColor.opacity(Self.fillOpacity))
          .padding(.vertical, Self.insetVertical)
          .padding(.horizontal, Self.insetHorizontal)
      }
    }
  }
}

extension View {
  func selectedRowHighlight(isSelected: Bool) -> some View {
    modifier(SelectedRowHighlight(isSelected: isSelected))
  }
}
