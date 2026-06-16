import SwiftUI
import MC1Services

/// Fragment-level wrapper over the body text renderer, driven by a typed `MessageTextPayload`
/// data carrier. Renders through `MessageBodyTextView` (a `UITextView`-backed representable) so
/// the bubble's long-press wins over link interaction while link taps still route; the SwiftUI
/// `Text(AttributedString)` path it replaced installed its own long-press link menu that
/// out-prioritized the bubble's gesture.
struct MessageTextView: View {
    let text: MessageTextPayload

    // Reflow of already-visible bubbles on a Dynamic Type change is driven primarily by the
    // AppearanceToken reconfigure re-host; this per-view token is a secondary discriminator that
    // guards the in-Coordinator size cache so a recycled view cannot reuse a height from another size.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        MessageBodyTextView(
            attributedString: text.formatted ?? AttributedString(text.raw),
            baseColor: text.baseColor.swiftUIColor,
            contentSizeCategoryToken: AppearanceToken.contentSizeCategoryToken(dynamicTypeSize)
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
