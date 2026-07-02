import SwiftUI

/// Color-fade overlay (iOS 26) that segments edge-to-edge chat content from the translucent
/// nav bar (top) and input bar (bottom) as it scrolls behind them. Shared by the DM/channel
/// chat (`ChatMessagesTableView`) and rooms (`RoomConversationView`).
struct ChatEdgeFadeOverlay: View {
  let topInset: CGFloat
  let bottomInset: CGFloat
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

      LinearGradient(
        stops: [
          .init(color: canvas.opacity(0), location: 0),
          .init(color: canvas.opacity(0.2), location: 0.4),
          .init(color: canvas.opacity(0.9), location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: bottomInset)
    }
    .allowsHitTesting(false)
    .ignoresSafeArea()
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
  func chatEdgeFade(topInset: CGFloat, bottomInset: CGFloat, canvas: Color) -> some View {
    if #available(iOS 26.0, *) {
      overlay {
        ChatEdgeFadeOverlay(topInset: topInset, bottomInset: bottomInset, canvas: canvas)
      }
    } else {
      self
    }
  }
}
