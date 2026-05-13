import Foundation
import MC1Services

/// Pure-function predicates for `UnifiedMessageBubble` footer rendering and accessibility composition.
///
/// Inputs flow from `MessageFooter` (populated by `MessageFragmentBuilder`).
/// `MessageBubblePredicateTests` exercise the predicate algebra in isolation;
/// production code now consumes the predicate values directly off `MessageFooter`.
struct MessageBubblePredicates {
    let isFloodRouted: Bool
    let regionScope: String?
    let showIncomingHopCount: Bool
    let showIncomingRegion: Bool
    let formattedPath: String?

    /// True when the hop-count footer should render.
    var showHop: Bool {
        showIncomingHopCount && isFloodRouted
    }

    /// The region label to render in the footer, or nil if it should not show.
    var regionToShow: String? {
        guard showIncomingRegion, isFloodRouted else { return nil }
        return regionScope
    }

    /// True when any of the three footer slots (hop, path, region) should render.
    var hasFooter: Bool {
        showHop || formattedPath != nil || regionToShow != nil
    }
}
