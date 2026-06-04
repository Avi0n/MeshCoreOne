import SwiftUI

/// A settings list row that opens a `SettingsDetail` page. The value-based `NavigationLink` pushes
/// onto the compact stack (via `SettingsView`'s `navigationDestination`) and drives the iPad split's
/// `List(selection:)` binding (`NavigationCoordinator.selectedSetting`, read by `SettingsDetailView`
/// in the detail column). On the iPad the selected row draws the accent capsule itself: the
/// section's themed `listRowBackground` otherwise suppresses the native selection highlight, and the
/// system never inverts an explicit `.tint` icon onto the capsule.
struct SettingsDetailRow<Label: View>: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    let detail: SettingsDetail
    let isSidebar: Bool
    @ViewBuilder let label: () -> Label

    var body: some View {
        NavigationLink(value: detail) {
            label()
        }
        .modifier(SelectedSettingRowModifier(
            theme: theme,
            // Compact pushes onto a stack with no persistent selection, so the capsule is iPad-only.
            isSelected: isSidebar && appState.navigation.selectedSetting == detail
        ))
    }
}

/// Replaces the section's themed background on the selected iPad settings row with the accent
/// capsule and a dark color scheme, so the capsule shows on every theme and the title/detail text
/// invert to light against it. `TintedLabel` paints its icon with an explicit `.tint`, which the
/// dark scheme can't invert, so the tint is overridden to white here to match the title rather than
/// blend into the accent capsule. The grouped list already insets the row background, so the capsule
/// fills it edge-to-edge (no horizontal inset) and frames the row's own margins. A no-op when
/// unselected, leaving the section's themed background in place.
private struct SelectedSettingRowModifier: ViewModifier {
    let theme: Theme
    let isSelected: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if isSelected {
            content
                .environment(\.colorScheme, .dark)
                .tint(.white)
                .listRowBackground(SelectionCapsule(theme: theme, horizontalInset: 0))
        } else {
            content
        }
    }
}
