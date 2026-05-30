import SwiftUI

/// An owned theme on the Appearance screen: swatch + name + (Selected badge | tap to apply).
struct ThemeSelectionCard: View {
    let theme: Theme
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                ThemePaletteSwatch(theme: theme)
                Text(theme.localizedName)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                if isSelected {
                    HStack(spacing: ThemeCardMetrics.badgeIconSpacing) {
                        Image(systemName: "checkmark")
                        Text(L10n.Settings.Appearance.Themes.selected)
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                } else {
                    Color.clear.frame(height: 1)
                }
            }
            .padding(ThemeCardMetrics.contentPadding)
            .frame(maxWidth: .infinity)
            .overlay(
                RoundedRectangle(cornerRadius: ThemeCardMetrics.cornerRadius)
                    .strokeBorder(isSelected ? Color.accentColor : .clear, lineWidth: ThemeCardMetrics.selectionStrokeWidth)
            )
        }
        .buttonStyle(.plain)
        .disabled(isSelected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(isSelected
            ? L10n.Settings.Appearance.Accessibility.ThemeCard.selectedLabel(theme.localizedName)
            : L10n.Settings.Appearance.Accessibility.ThemeCard.ownedLabel(theme.localizedName))
        .accessibilityHint(isSelected ? "" : L10n.Settings.Appearance.Accessibility.ThemeCard.ownedHint)
    }
}
