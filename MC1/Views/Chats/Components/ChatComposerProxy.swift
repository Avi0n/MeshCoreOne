import UIKit

/// Bridges a SwiftUI send action to the live composer text view, letting the
/// input bar finalize an in-progress IME composition before it reads the text to
/// send. `ChatComposerTextView` wires its text view in on creation.
@MainActor
final class ChatComposerProxy {
    weak var textView: ChatComposerUITextView?

    func commitPendingInput() {
        textView?.commitPendingInput()
    }
}
