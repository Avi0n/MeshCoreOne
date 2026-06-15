import SwiftUI

/// UIKit-backed long-press that drives the bubble's press-in lift and gates the
/// message actions sheet.
///
/// A SwiftUI `.onLongPressGesture` claims the touch and blocks the flipped
/// table's pan recognizer regardless of `.simultaneousGesture`, so a
/// `UILongPressGestureRecognizer` is used instead. On recognition it reports the
/// press-in so the bubble lifts; the sheet itself opens a beat later, scheduled
/// by the view from the same press state, so the lifted bubble shows before the
/// sheet rises. The delegate grants simultaneity only to the scroll pan (a press
/// that becomes a drag yields to scrolling) and requires every other in-bubble
/// gesture to fail first, so a held press wins over the inline-image, GIF, and
/// link recognizers while a quick tap still reaches them.
struct BubbleLongPressGesture: UIGestureRecognizerRepresentable {
    /// Hold before the bubble lifts. Long enough that a quick tap passes through
    /// to a child recognizer, short enough that the lift feels responsive.
    private static let minimumPressDuration: TimeInterval = 0.25

    let onPressChanged: (Bool) -> Void

    func makeUIGestureRecognizer(context: Context) -> UILongPressGestureRecognizer {
        let recognizer = UILongPressGestureRecognizer()
        recognizer.minimumPressDuration = Self.minimumPressDuration
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func handleUIGestureRecognizerAction(_ recognizer: UILongPressGestureRecognizer, context: Context) {
        switch recognizer.state {
        case .began:
            onPressChanged(true)
        case .ended, .cancelled:
            onPressChanged(false)
        default:
            break
        }
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

        // Every in-bubble gesture except the scroll pan must fail before this press.
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            !(otherGestureRecognizer is UIPanGestureRecognizer)
        }
    }
}
