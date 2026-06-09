import SwiftUI

/// A single theme preview in the bundle showcase: swatch + name + locked/owned state.
/// Non-interactive — the bundle card is the only purchase control; applying happens on the
/// Appearance screen.
struct ThemePreviewCard: View {
    let theme: Theme
    let isOwned: Bool

    var body: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topTrailing) {
                ThemePaletteSwatch(theme: theme)
                if !isOwned {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .padding(6)
                        .background(.ultraThinMaterial, in: Circle())
                        .padding(6)
                }
            }
            Text(theme.localizedName)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
            if isOwned {
                HStack(spacing: ThemeCardMetrics.badgeIconSpacing) {
                    Image(systemName: "checkmark")
                    Text(L10n.Settings.Support.Themes.owned)
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            }
        }
        .padding(ThemeCardMetrics.contentPadding)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        isOwned
            ? L10n.Settings.Support.Accessibility.ThemeCard.ownedLabel(theme.localizedName)
            : L10n.Settings.Support.Accessibility.ThemeCard.lockedLabel(theme.localizedName)
    }
}
