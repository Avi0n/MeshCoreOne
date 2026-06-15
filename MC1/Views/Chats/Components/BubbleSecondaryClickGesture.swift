import SwiftUI

/// UIKit-backed secondary (right / two-finger trackpad) click that opens the
/// message actions sheet, giving pointer users the same surface a long-press
/// gives touch users.
///
/// Wired per-bubble like the long-press rather than on the table, so a surface
/// that does not opt in (rooms) never installs it and never suppresses the
/// native context menu. `cancelsTouchesInView` stays false and the delegate
/// grants simultaneity so the click rides alongside any in-bubble recognizer
/// (a link-preview button, a Text link) instead of being swallowed by it.
struct BubbleSecondaryClickGesture: UIGestureRecognizerRepresentable {
    let onSecondaryClick: () -> Void

    func makeUIGestureRecognizer(context: Context) -> UITapGestureRecognizer {
        let recognizer = UITapGestureRecognizer()
        // The secondary button mask keeps this off the primary touch and scroll.
        recognizer.buttonMaskRequired = .secondary
        recognizer.cancelsTouchesInView = false
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func handleUIGestureRecognizerAction(_ recognizer: UITapGestureRecognizer, context: Context) {
        guard recognizer.state == .recognized else { return }
        onSecondaryClick()
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}
