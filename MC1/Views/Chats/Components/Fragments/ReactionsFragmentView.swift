import SwiftUI
import MC1Services

/// Fragment-level view that renders the reactions slot of a message bubble.
/// Wraps `ReactionBadgesView` with the offset and bottom padding the bubble
/// applies inline so the call site stays a single typed input.
struct ReactionsFragmentView: View {
    /// Raw summary string (`"👍:3,❤️:2"`). Non-optional because the
    /// fragment is only emitted for non-nil non-empty values.
    /// `ReactionBadgesView` takes `summary: String?`; Swift auto-wraps the
    /// non-optional into `Optional` at the call site.
    let summary: String
    let onTapReaction: (String) -> Void
    let onLongPress: () -> Void

    var body: some View {
        ReactionBadgesView(
            summary: summary,
            onTapReaction: onTapReaction,
            onLongPress: onLongPress
        )
        .offset(y: -6)
        .padding(.bottom, -6)
    }
}
