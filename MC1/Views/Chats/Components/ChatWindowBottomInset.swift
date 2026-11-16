import SwiftUI
import UIKit

/// Reserves the window home-indicator inset on compact layouts.
/// The chrome supplies the height. A descendant inset can include the tab bar while it hides.
struct ChatWindowBottomInset: View {
  private let heightOverride: CGFloat?
  @Environment(\.chatWindowHomeInset) private var homeInset
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  init() {
    heightOverride = nil
  }

  init(height: CGFloat) {
    heightOverride = height
  }

  private var height: CGFloat {
    heightOverride ?? homeInset
  }

  var body: some View {
    if horizontalSizeClass == .compact, height > 0 {
      Representable(height: height)
        .frame(height: height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
  }

  private struct Representable: UIViewRepresentable {
    var height: CGFloat

    func makeUIView(context: Context) -> ChatWindowBottomInsetHost {
      let host = ChatWindowBottomInsetHost()
      host.isUserInteractionEnabled = false
      host.accessibilityElementsHidden = true
      host.reservedHeight = height
      return host
    }

    func updateUIView(_ uiView: ChatWindowBottomInsetHost, context: Context) {
      uiView.reservedHeight = height
    }
  }
}

extension EnvironmentValues {
  /// Window home-indicator inset reserved under the compact composer and mention list.
  @Entry var chatWindowHomeInset: CGFloat = 0
}
