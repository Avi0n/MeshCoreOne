import SwiftUI

/// Shared geometry for the theme cards on the Support and Appearance screens. Keeping the corner
/// radius in one place stops a card's background fill and its selection-stroke overlay from
/// drifting out of sync.
enum ThemeCardMetrics {
    static let cornerRadius: CGFloat = 14
    static let contentPadding: CGFloat = 8
    static let selectionStrokeWidth: CGFloat = 2
    static let badgeIconSpacing: CGFloat = 3

    /// Shared theme-grid layout, kept here so the Appearance and Support card grids cannot drift.
    static let gridItemMinimum: CGFloat = 160
    static let gridSpacing: CGFloat = 12
    static let gridRowInsets = EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)

    /// Layout for the "all themes unlocked" celebration that replaces the card grid once every
    /// purchasable theme is owned.
    static let allUnlockedEmojiSize: CGFloat = 56
    static let allUnlockedSpacing: CGFloat = 12
    static let allUnlockedVerticalPadding: CGFloat = 24
}
