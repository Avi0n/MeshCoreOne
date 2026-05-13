import SwiftUI

/// Fragment-level wrapper over the existing `MessageText` SwiftUI view, driven
/// by a typed MessageTextPayload data carrier.
struct MessageTextView: View {
    let text: MessageTextPayload

    var body: some View {
        MessageText(
            text.raw,
            baseColor: text.baseColor,
            isOutgoing: text.isOutgoing,
            currentUserName: text.currentUserName,
            precomputedText: text.formatted
        )
    }
}
