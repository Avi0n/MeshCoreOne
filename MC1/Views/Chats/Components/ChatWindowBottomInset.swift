import SwiftUI
import UIKit

/// Window home-indicator height, empty on regular width. The view's own
/// bottom inset still includes the tab bar for a frame after hide, and
/// reading that inset drops the compose bar once the chat is on screen.
struct ChatWindowBottomInset: View {
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  var body: some View {
    if horizontalSizeClass == .compact {
      Representable()
        .fixedSize(horizontal: false, vertical: true)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
  }

  private struct Representable: UIViewRepresentable {
    func makeUIView(context: Context) -> Host {
      let host = Host()
      host.seedHeight = ChatKeyboardLift.keyWindow()?.safeAreaInsets.bottom
      return host
    }

    func updateUIView(_ uiView: Host, context: Context) {}

    final class Host: UIView {
      var seedHeight: CGFloat?
      private var appliedHeight: CGFloat?

      override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: resolvedHeight)
      }

      override func didMoveToWindow() {
        super.didMoveToWindow()
        invalidateIfNeeded()
      }

      override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        // The view inset changes when the tab bar hides. The window inset does not.
        invalidateIfNeeded()
      }

      private var resolvedHeight: CGFloat {
        window?.safeAreaInsets.bottom ?? seedHeight ?? 0
      }

      private func invalidateIfNeeded() {
        let height = resolvedHeight
        guard height != appliedHeight else { return }
        appliedHeight = height
        invalidateIntrinsicContentSize()
      }
    }
  }
}

extension View {
  /// On compact, ignore the bottom container and keyboard insets together.
  /// A container-only ignore turns keyboard avoidance back on under the owned
  /// lift. Apply outside the safe-area inset; inside it, the inset does not move.
  func chatIgnoresLaggingTabBarInset() -> some View {
    modifier(ChatLaggingTabBarInsetModifier())
  }
}

private struct ChatLaggingTabBarInsetModifier: ViewModifier {
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  func body(content: Content) -> some View {
    if horizontalSizeClass == .compact {
      content.ignoresSafeArea([.container, .keyboard], edges: .bottom)
    } else {
      content
    }
  }
}
