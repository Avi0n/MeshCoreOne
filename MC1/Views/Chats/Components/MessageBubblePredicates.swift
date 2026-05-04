import Foundation
import MC1Services

/// Pure-function predicates for `UnifiedMessageBubble` footer rendering and accessibility composition.
///
/// Both consumers (`BubbleContent.body` and `UnifiedMessageBubble.accessibilityMessageLabel`)
/// construct a single instance and read the same gated outputs. This eliminates the
/// duplicate gate logic that previously allowed the region footer to render on
/// direct-routed messages even though the hop footer correctly suppressed itself.
struct MessageBubblePredicates {
    let message: MessageDTO
    let displayState: MessageDisplayState

    /// True when the hop-count footer should render.
    var showHop: Bool {
        displayState.showIncomingHopCount && message.isFloodRouted
    }
}
