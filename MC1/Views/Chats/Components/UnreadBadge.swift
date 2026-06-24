import SwiftUI

/// Capsule count badge overlaid on the scroll buttons. Shared by the
/// scroll-to-bottom (unread) and scroll-to-mention buttons; only the count and
/// tint differ between them.
struct UnreadBadge: View {
    let count: Int
    let tint: Color

    /// Counts above this render as an overflow glyph rather than the exact number.
    static let badgeOverflowThreshold = 99

    var body: some View {
        if count > 0 {
            Text(count > Self.badgeOverflowThreshold ? L10n.Chats.Chats.ScrollButton.Badge.overflow : "\(count)")
                .font(.caption2.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(tint, in: .capsule)
                .offset(x: 8, y: -8)
        }
    }
}
