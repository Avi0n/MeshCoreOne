import SwiftUI

struct ChatBottomChrome<Content: View>: View {
  let canvas: Color
  var homeInset: CGFloat
  @ViewBuilder var content: () -> Content

  var body: some View {
    VStack(spacing: 0) {
      content()
        .chatKeyboardLiftPadding()
      ChatWindowBottomInset(height: homeInset)
    }
    .chatComposeBarFade(canvas: canvas)
  }
}
