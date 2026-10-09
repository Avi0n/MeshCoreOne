import SwiftUI
import UIKit

/// Use the window home-indicator inset on compact layouts.
/// Descendant insets can briefly include the tab bar while it hides.
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
