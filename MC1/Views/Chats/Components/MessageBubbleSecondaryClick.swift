import SwiftUI
import UIKit

/// A transparent catcher for a secondary click (right click, trackpad two-finger click, or
/// control-click), used on Mac to open a message bubble's actions sheet — the shortcut a secondary
/// click implies, matching the sustained press that opens it elsewhere.
///
/// It installs a `UIContextMenuInteraction`, the single interaction the "Designed for iPad" runtime
/// routes every secondary-click affordance through, and suppresses the system menu (returns `nil`),
/// firing our own action instead. A plain tap recognizer with `buttonMaskRequired = .secondary`
/// catches only a control-click — a trackpad two-finger click never arrives as a button event, only
/// as this context-menu request.
///
/// Off Mac it is never installed: there is no secondary click, and a `UIContextMenuInteraction`
/// there would fire on long-press and fight the bubble's own long-press.
private struct SecondaryClickCatcher: UIViewRepresentable {
  let onSecondaryClick: () -> Void

  func makeCoordinator() -> Coordinator {
    Coordinator(onSecondaryClick: onSecondaryClick)
  }

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.backgroundColor = .clear
    view.addInteraction(UIContextMenuInteraction(delegate: context.coordinator))
    return view
  }

  func updateUIView(_ uiView: UIView, context: Context) {
    context.coordinator.onSecondaryClick = onSecondaryClick
  }

  @MainActor
  final class Coordinator: NSObject, UIContextMenuInteractionDelegate {
    var onSecondaryClick: () -> Void

    init(onSecondaryClick: @escaping () -> Void) {
      self.onSecondaryClick = onSecondaryClick
    }

    func contextMenuInteraction(
      _ interaction: UIContextMenuInteraction,
      configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
      // Defer past this synchronous delegate call so presenting the sheet doesn't race the
      // interaction's own teardown; return nil so no system menu appears.
      DispatchQueue.main.async { [onSecondaryClick] in onSecondaryClick() }
      return nil
    }
  }
}

extension View {
  /// Fires `perform` on a secondary click, on Mac only; a no-op elsewhere.
  @ViewBuilder
  func onSecondaryClick(perform: @escaping () -> Void) -> some View {
    if ProcessInfo.processInfo.isiOSAppOnMac {
      overlay { SecondaryClickCatcher(onSecondaryClick: perform) }
    } else {
      self
    }
  }
}
