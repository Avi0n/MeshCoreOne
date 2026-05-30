import SwiftUI
import MC1Services

/// Avatar view for remote nodes (room servers and repeaters). All repeaters share one theme color,
/// all room servers share another — distinct per category, fixed per theme.
struct NodeAvatar: View {
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    let publicKey: Data
    let role: RemoteNodeRole
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(fill)

            Image(systemName: iconName)
                .font(.system(size: size * 0.45, weight: .semibold))
                .foregroundStyle(glyph)
        }
        .frame(width: size, height: size)
    }

    private var iconName: String {
        switch role {
        case .roomServer:
            return "door.left.hand.closed"
        case .repeater:
            return "antenna.radiowaves.left.and.right"
        }
    }

    private var category: AvatarCategory {
        role == .roomServer ? .room : .repeater
    }

    private var fill: Color {
        theme.categoryAvatarColor(category, colorScheme: colorScheme, contrast: colorSchemeContrast)
    }

    private var glyph: Color {
        theme.avatarGlyphColor(
            forFill: fill,
            usesCategoryOverride: theme.usesCategoryAvatarOverride,
            colorScheme: colorScheme,
            contrast: colorSchemeContrast
        )
    }
}

#Preview("Room Server") {
    NodeAvatar(
        publicKey: Data(repeating: 0x42, count: 32),
        role: .roomServer,
        size: 60
    )
}

#Preview("Repeater") {
    NodeAvatar(
        publicKey: Data(repeating: 0x55, count: 32),
        role: .repeater,
        size: 60
    )
}
