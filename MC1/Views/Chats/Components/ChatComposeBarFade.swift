import SwiftUI

/// Fade behind the floating compose bar: transparent at the bar's top edge so messages stay
/// legible until they scroll under the bar, opaque toward the bottom. Applied as the input
/// bar's background so it lifts with the bar and the keyboard, and extends over the home
/// indicator (when the keyboard is down) via the bottom container safe area.
///
/// The chat list is already edge-to-edge (the `TiledView` collection view ignores the safe
/// area and reserves it as content inset), so no separate edge-to-edge modifier is needed and
/// there is intentionally no top fade.
@available(iOS 26.0, *)
private struct ChatComposeBarFade: View {
  let canvas: Color

  var body: some View {
    LinearGradient(
      stops: [
        .init(color: canvas.opacity(0), location: 0),
        .init(color: canvas.opacity(0.2), location: 0.4),
        .init(color: canvas.opacity(0.9), location: 1),
      ],
      startPoint: .top,
      endPoint: .bottom
    )
    .ignoresSafeArea(.container, edges: .bottom)
    .allowsHitTesting(false)
  }
}

extension View {
  @ViewBuilder
  func chatComposeBarFade(canvas: Color) -> some View {
    if #available(iOS 26.0, *) {
      background { ChatComposeBarFade(canvas: canvas) }
    } else {
      self
    }
  }
}
