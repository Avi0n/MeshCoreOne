import SwiftUI

/// Top color-fade (iOS 26) that segments edge-to-edge chat content from the translucent nav
/// bar as it scrolls behind it. The bottom edge is handled by `chatComposeBarFade`, which
/// attaches to the input bar so the fade tracks the bar (and the keyboard) directly.
struct ChatTopEdgeFade: View {
  let topInset: CGFloat
  let canvas: Color

  var body: some View {
    VStack(spacing: 0) {
      LinearGradient(
        stops: [
          .init(color: canvas.opacity(0.99), location: 0),
          .init(color: canvas.opacity(0.5), location: 0.75),
          .init(color: canvas.opacity(0), location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: topInset)

      Spacer(minLength: 0)
    }
    .allowsHitTesting(false)
    .ignoresSafeArea(.container, edges: .top)
  }
}

/// Fade behind the floating compose bar: transparent at the bar's top edge so messages stay
/// legible until they scroll under the bar, opaque toward the bottom. Applied as the input
/// bar's background so it lifts with the bar and the keyboard, and extends over the home
/// indicator (when the keyboard is down) via the bottom container safe area.
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
  func chatEdgeToEdge() -> some View {
    if #available(iOS 26.0, *) {
      ignoresSafeArea(.all, edges: [.top, .bottom])
    } else {
      self
    }
  }

  @ViewBuilder
  func chatEdgeFade(topInset: CGFloat, canvas: Color) -> some View {
    if #available(iOS 26.0, *) {
      overlay { ChatTopEdgeFade(topInset: topInset, canvas: canvas) }
    } else {
      self
    }
  }

  @ViewBuilder
  func chatComposeBarFade(canvas: Color) -> some View {
    if #available(iOS 26.0, *) {
      background { ChatComposeBarFade(canvas: canvas) }
    } else {
      self
    }
  }
}
