import SwiftUI

/// Shared theme preview for `ThemePreviewCard` and `ThemeSelectionCard`. The diagonal split
/// signals light+dark support; an un-split swatch signals a dark-only theme (Ember).
struct ThemePaletteSwatch: View {
    let theme: Theme
    /// `.tappable` enforces the 44pt HIG tap-target minimum (default; used by the preview and
    /// selection cards). `.thumbnail` drops the minimum so a row of many swatches can fit a
    /// shared parent width.
    var size: SizeStyle = .tappable

    enum SizeStyle: Sendable {
        case tappable
        case thumbnail
    }

    private enum Layout {
        static let tappableMinSide: CGFloat = 44
        static let corner: CGFloat = 12
        static let bubbleCorner: CGFloat = 6
        static let bubbleInset: CGFloat = 8
    }

    private var isDualMode: Bool { theme.preferredColorScheme == nil }

    private var enforcedMinSide: CGFloat? {
        size == .tappable ? Layout.tappableMinSide : nil
    }

    var body: some View {
        ZStack {
            if isDualMode {
                palette
                    .environment(\.colorScheme, .light)
                    .clipShape(DiagonalHalf(top: true))
                palette
                    .environment(\.colorScheme, .dark)
                    .clipShape(DiagonalHalf(top: false))
            } else {
                palette
                    .environment(\.colorScheme, .dark)
            }
        }
        .frame(minWidth: enforcedMinSide, minHeight: enforcedMinSide)
        .clipShape(RoundedRectangle(cornerRadius: Layout.corner))
        .accessibilityElement()
        .accessibilityLabel(accessibilityLabel)
    }

    /// Accent + background + a sample outgoing bubble, all theme-driven.
    private var palette: some View {
        ZStack {
            (theme.surfaces?.canvas ?? Color(.secondarySystemBackground))
            RoundedRectangle(cornerRadius: Layout.bubbleCorner)
                .fill(theme.accentColor)
                .padding(Layout.bubbleInset)
        }
    }

    private var accessibilityLabel: String {
        isDualMode
            ? L10n.Settings.Appearance.Accessibility.Swatch.dualMode(theme.localizedName)
            : L10n.Settings.Appearance.Accessibility.Swatch.darkOnly(theme.localizedName)
    }
}

/// One triangular half of the swatch, split corner-to-corner (top-leading triangle when `top`).
private struct DiagonalHalf: Shape {
    let top: Bool

    func path(in rect: CGRect) -> Path {
        var path = Path()
        if top {
            path.move(to: CGPoint(x: rect.minX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        } else {
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        }
        path.closeSubpath()
        return path
    }
}
