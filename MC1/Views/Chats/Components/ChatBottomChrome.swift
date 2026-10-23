import SwiftUI

struct ChatBottomChrome<Content: View>: View {
  let canvas: Color
  @ViewBuilder var content: () -> Content

  var body: some View {
    VStack(spacing: 0) {
      content()
        .chatKeyboardLiftPadding()
      ChatWindowBottomInset()
    }
    .chatComposeBarFade(canvas: canvas)
  }
}
