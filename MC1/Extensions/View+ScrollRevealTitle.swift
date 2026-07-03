import SwiftUI

extension View {
  /// Leaves the navigation bar title empty while the header is on screen, then fades
  /// in `title` (the entity name) once the user scrolls past `revealAfter` points.
  /// Pairs with `.navigationBarTitleDisplayMode(.inline)`.
  func scrollRevealNavigationTitle(_ title: String, revealAfter: CGFloat = 150) -> some View {
    modifier(ScrollRevealNavigationTitle(title: title, revealAfter: revealAfter))
  }
}

private struct ScrollRevealNavigationTitle: ViewModifier {
  let title: String
  let revealAfter: CGFloat

  @State private var isRevealed = false

  func body(content: Content) -> some View {
    content
      .onScrollGeometryChange(for: Bool.self) { geometry in
        geometry.contentOffset.y > revealAfter
      } action: { _, revealed in
        guard revealed != isRevealed else { return }
        withAnimation(.easeInOut(duration: 0.2)) { isRevealed = revealed }
      }
      .toolbar {
        ToolbarItem(placement: .principal) {
          Text(title)
            .font(.headline)
            .opacity(isRevealed ? 1 : 0)
        }
      }
  }
}
