import SwiftUI

/// UIKit-backed single tap that opens a URL-only message's link.
///
/// A message whose entire text is one URL renders without a live `.link` so
/// SwiftUI's own link gesture cannot claim the touch ahead of the bubble's
/// long-press; this tap restores opening it from inside the same UIKit gesture
/// arena. The long-press requires every non-pan recognizer to fail first, so a
/// quick tap opens the link while a held press still opens the actions sheet.
/// When the message is not URL-only the recognizer is disabled and inert.
struct BubbleTapGesture: UIGestureRecognizerRepresentable {
    let url: URL?
    let onTap: (URL) -> Void

    func makeUIGestureRecognizer(context: Context) -> UITapGestureRecognizer {
        let recognizer = UITapGestureRecognizer()
        // Opening the link is a primary-tap affordance only; a secondary click
        // opens the actions sheet and must not also navigate away.
        recognizer.buttonMaskRequired = .primary
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: UITapGestureRecognizer, context: Context) {
        recognizer.isEnabled = url != nil
    }

    func handleUIGestureRecognizerAction(_ recognizer: UITapGestureRecognizer, context: Context) {
        guard recognizer.state == .recognized, let url else { return }
        onTap(url)
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            otherGestureRecognizer is UIPanGestureRecognizer
        }
    }
}
