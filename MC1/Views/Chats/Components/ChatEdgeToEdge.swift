import SwiftUI

/// Color-fade overlay (iOS 26) that segments edge-to-edge chat content from the translucent
/// nav bar (top) and input bar (bottom) as it scrolls behind them. Shared by the DM/channel
/// chat (`ChatMessagesTableView`) and rooms (`RoomConversationView`).
struct ChatEdgeFadeOverlay: View {
  let topInset: CGFloat
  let bottomInset: CGFloat
  let canvas: Color

  /// Extra distance past the bar the fade runs into the content, so it tapers over a longer
  /// stretch rather than washing out inside the inset.
  private let fadeRun: CGFloat = 28

  var body: some View {
    VStack(spacing: 0) {
      LinearGradient(
        stops: [
          .init(color: canvas, location: 0),
          .init(color: canvas.opacity(0.65), location: 0.5),
          .init(color: canvas.opacity(0), location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: topInset + fadeRun)

      Spacer(minLength: 0)

      LinearGradient(
        stops: [
          .init(color: canvas.opacity(0), location: 0),
          .init(color: canvas.opacity(0.65), location: 0.5),
          .init(color: canvas, location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: bottomInset + fadeRun)
    }
    .allowsHitTesting(false)
    .ignoresSafeArea()
  }
}

extension View {
  /// iOS 26: extend the flipped chat table edge-to-edge behind the translucent bars. `.container`
  /// drops the nav/input-bar insets but keeps `.keyboard`, so keyboard avoidance still works.
  @ViewBuilder
  func chatEdgeToEdge() -> some View {
    if #available(iOS 26.0, *) {
      ignoresSafeArea(.container, edges: [.top, .bottom])
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

  /// iOS 26: the table extends behind the input bar, so overlay scroll buttons must clear it.
  func chatScrollButtonBottomPadding(_ insets: EdgeInsets) -> some View {
    let bottom: CGFloat = if #available(iOS 26.0, *) { insets.bottom + 8 } else { 8 }
    return padding(.bottom, bottom)
  }
}
