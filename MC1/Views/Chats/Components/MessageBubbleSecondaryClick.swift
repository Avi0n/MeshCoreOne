import SwiftUI
import UIKit

/// A transparent catcher for a secondary (right) click, used on Mac to open a message bubble's
/// actions sheet — the shortcut a right click implies, matching the sustained press that opens it
/// elsewhere. The recognizer requires the secondary button and does not consume touches, so it
/// never competes with scrolling, a primary tap, or the bubble's long-press.
///
/// Off Mac it is never installed: there is no secondary click, and the long-press already covers
/// the interaction.
private struct SecondaryClickCatcher: UIViewRepresentable {
  let onSecondaryClick: () -> Void

  func makeCoordinator() -> Coordinator {
    Coordinator(onSecondaryClick: onSecondaryClick)
  }

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.backgroundColor = .clear
    let recognizer = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleClick)
    )
    recognizer.buttonMaskRequired = .secondary
    recognizer.cancelsTouchesInView = false
    view.addGestureRecognizer(recognizer)
    return view
  }

  func updateUIView(_ uiView: UIView, context: Context) {
    context.coordinator.onSecondaryClick = onSecondaryClick
  }

  @MainActor
  final class Coordinator: NSObject {
    var onSecondaryClick: () -> Void

    init(onSecondaryClick: @escaping () -> Void) {
      self.onSecondaryClick = onSecondaryClick
    }

    @objc func handleClick() {
      onSecondaryClick()
    }
  }
}

extension View {
  /// Fires `perform` on a secondary (right) click, on Mac only; a no-op elsewhere.
  @ViewBuilder
  func onSecondaryClick(perform: @escaping () -> Void) -> some View {
    if ProcessInfo.processInfo.isiOSAppOnMac {
      overlay { SecondaryClickCatcher(onSecondaryClick: perform) }
    } else {
      self
    }
  }
}
