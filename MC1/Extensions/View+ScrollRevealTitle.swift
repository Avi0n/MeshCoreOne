import SwiftUI

extension View {
  /// Leaves the navigation bar title empty while the header is on screen, then fades
  /// in `title` (the entity name) once the user scrolls past `revealAfter` points.
  /// Pass the header's measured height (see `scrollRevealHeaderHeight`) so the reveal
  /// tracks the real header size and adapts to Dynamic Type.
  /// Pairs with `.navigationBarTitleDisplayMode(.inline)`.
  func scrollRevealNavigationTitle(_ title: String, revealAfter: CGFloat = 150) -> some View {
    modifier(ScrollRevealNavigationTitle(title: title, revealAfter: revealAfter))
  }

  /// Reports the measured height of the scroll-reveal header into `height`, so the
  /// title reveals based on the header's real (Dynamic Type-aware) size rather than a
  /// fixed threshold. Attach to the header content, then feed `height` to
  /// `scrollRevealNavigationTitle(_:revealAfter:)`.
  func scrollRevealHeaderHeight(_ height: Binding<CGFloat>) -> some View {
    onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height.wrappedValue = $0 }
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
