import SwiftUI
import UIKit

/// A transparent tap catcher for an interactive bubble fragment (GIF, image, preview card). A quick
/// tap routes to `onTap`; a sustained press yields to the bubble's long-press so the actions sheet
/// opens. It replaces a SwiftUI `Button`, whose press gesture grabs the touch on touch-down and
/// cancels the bubble's long-press.
///
/// The `UITapGestureRecognizer` does not consume touches (`cancelsTouchesInView = false`), and its
/// delegate denies simultaneous recognition with a `UILongPressGestureRecognizer` so the bubble's
/// long-press wins a contested press. The delegate is set only off Mac; on Mac the secondary click
/// routes through the table's context-menu interaction, which this delegate must not disturb.
struct TapYieldingToLongPress: UIViewRepresentable {
  let onTap: () -> Void

  func makeCoordinator() -> Coordinator {
    Coordinator(onTap: onTap)
  }

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.backgroundColor = .clear
    view.addGestureRecognizer(
      Self.makeRecognizer(
        coordinator: context.coordinator,
        isMac: ProcessInfo.processInfo.isiOSAppOnMac
      )
    )
    return view
  }

  func updateUIView(_ uiView: UIView, context: Context) {
    context.coordinator.onTap = onTap
  }

  /// Builds the tap recognizer with the yielding policy. Pure and `isMac`-parameterized so the
  /// `cancelsTouchesInView` flag and the off-Mac delegate gate can be exercised in a unit test.
  static func makeRecognizer(coordinator: Coordinator, isMac: Bool) -> UITapGestureRecognizer {
    let recognizer = UITapGestureRecognizer(
      target: coordinator,
      action: #selector(Coordinator.handleTap)
    )
    recognizer.cancelsTouchesInView = false
    if !isMac {
      recognizer.delegate = coordinator
    }
    return recognizer
  }

  @MainActor
  final class Coordinator: NSObject, UIGestureRecognizerDelegate {
    var onTap: () -> Void

    init(onTap: @escaping () -> Void) {
      self.onTap = onTap
    }

    @objc func handleTap() {
      onTap()
    }

    func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
      !(otherGestureRecognizer is UILongPressGestureRecognizer)
    }
  }
}

extension View {
  /// Routes a quick tap to `perform` while yielding a sustained press to the bubble's long-press.
  /// Use on interactive bubble fragments instead of a `Button`, whose press gesture would cancel
  /// the bubble's `.onLongPressGesture` before the actions sheet can open.
  func tapYieldingToLongPress(perform: @escaping () -> Void) -> some View {
    overlay { TapYieldingToLongPress(onTap: perform) }
  }
}
