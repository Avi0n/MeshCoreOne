import SwiftUI

/// Explicit per-category avatar colors, used only by the System (default) theme to pin the
/// channel / repeater / room avatars to their historical fixed values. Every other theme leaves
/// this `nil` and derives its three category colors from its `IdentityGamut` instead, which keeps
/// them on-theme and guarantees WCAG AA against the theme's surfaces.
struct CategoryAvatarColors: Sendable, Equatable {
    let channel: Color
    let repeaterNode: Color
    let room: Color

    func color(for category: AvatarCategory) -> Color {
        switch category {
        case .channel: return channel
        case .repeater: return repeaterNode
        case .room: return room
        }
    }
}
