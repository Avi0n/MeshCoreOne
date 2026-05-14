import SwiftUI
import MC1Services

/// Fragment-level wrapper over the existing `MessageText` SwiftUI view, driven
/// by a typed MessageTextPayload data carrier.
struct MessageTextView: View {
    let text: MessageTextPayload

    var body: some View {
        MessageText(
            text.raw,
            baseColor: text.baseColor.swiftUIColor,
            isOutgoing: text.isOutgoing,
            currentUserName: text.currentUserName,
            precomputedText: text.formatted
        )
    }
}

extension BaseColorSlot {
    /// Resolves the direction-tagged slot to a concrete SwiftUI `Color` at
    /// render time. Outgoing bubbles render on a filled background and need
    /// white text; incoming bubbles use the system primary colour. Lives in
    /// the MC1 layer because MC1Services intentionally has no SwiftUI
    /// dependency.
    var swiftUIColor: Color {
        switch self {
        case .outgoing: .white
        case .incoming: .primary
        }
    }
}
