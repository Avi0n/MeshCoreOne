import SwiftUI
import UIKit

/// Claims secondary-click and Control-click hits; unmodified primary clicks pass through
/// so links and card taps under the overlay still receive them.
final class SecondaryClickCatcherView: UIView {
  /// Secondary or Control+primary. Two-finger click is a context-menu request, not a button.
  nonisolated static func shouldReceiveButtonClick(
    buttonMask: UIEvent.ButtonMask,
    modifierFlags: UIKeyModifierFlags
  ) -> Bool {
    buttonMask.contains(.secondary)
      || (buttonMask.contains(.primary) && modifierFlags.contains(.control))
  }

  nonisolated static func shouldClaimHit(
    buttonMask: UIEvent.ButtonMask,
    modifierFlags: UIKeyModifierFlags = []
  ) -> Bool {
    shouldReceiveButtonClick(buttonMask: buttonMask, modifierFlags: modifierFlags)
      || !buttonMask.contains(.primary)
  }

  /// A nil event is claimed because two-finger click is a context-menu request, not a button.
  nonisolated static func shouldClaimHit(event: UIEvent?) -> Bool {
    guard let event else { return true }
    return shouldClaimHit(buttonMask: event.buttonMask, modifierFlags: event.modifierFlags)
  }

  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    guard self.point(inside: point, with: event) else { return nil }
    guard Self.shouldClaimHit(event: event) else { return nil }
    return self
  }
}

/// Opens the bubble actions sheet on Mac secondary click. Two-finger and right-click arrive
/// as a context-menu request; Control-click as a button tap. Off Mac this is never installed:
/// `UIContextMenuInteraction` would fire on long-press and fight the bubble's own long-press.
private struct SecondaryClickCatcher: UIViewRepresentable {
  let onSecondaryClick: () -> Void

  func makeCoordinator() -> Coordinator {
    Coordinator(onSecondaryClick: onSecondaryClick)
  }

  func makeUIView(context: Context) -> SecondaryClickCatcherView {
    let view = SecondaryClickCatcherView()
    view.backgroundColor = .clear
    view.addInteraction(UIContextMenuInteraction(delegate: context.coordinator))

    let secondaryTap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleButtonClick(_:))
    )
    secondaryTap.buttonMaskRequired = .secondary
    view.addGestureRecognizer(secondaryTap)

    let controlTap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleButtonClick(_:))
    )
    controlTap.buttonMaskRequired = .primary
    view.addGestureRecognizer(controlTap)
    return view
  }

  func updateUIView(_ uiView: SecondaryClickCatcherView, context: Context) {
    context.coordinator.onSecondaryClick = onSecondaryClick
  }

  @MainActor
  final class Coordinator: NSObject, UIContextMenuInteractionDelegate {
    var onSecondaryClick: () -> Void

    init(onSecondaryClick: @escaping () -> Void) {
      self.onSecondaryClick = onSecondaryClick
    }

    @objc func handleButtonClick(_ recognizer: UITapGestureRecognizer) {
      guard SecondaryClickCatcherView.shouldReceiveButtonClick(
        buttonMask: recognizer.buttonMask,
        modifierFlags: recognizer.modifierFlags
      ) else { return }
      emit()
    }

    func contextMenuInteraction(
      _ interaction: UIContextMenuInteraction,
      configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
      // Defer past this synchronous delegate call so presenting the sheet doesn't race the
      // interaction's own teardown; return nil so no system menu appears.
      emit()
      return nil
    }

    private func emit() {
      DispatchQueue.main.async { [onSecondaryClick] in onSecondaryClick() }
    }
  }
}

extension View {
  /// Fires `perform` on a secondary click or Control-click, on Mac only; a no-op elsewhere.
  @ViewBuilder
  func onSecondaryClick(perform: @escaping () -> Void) -> some View {
    if ProcessInfo.processInfo.isiOSAppOnMac {
      overlay { SecondaryClickCatcher(onSecondaryClick: perform) }
    } else {
      self
    }
  }
}
