import SwiftUI
import MC1Services

/// Settings sub-page for editing the user's `RegionSelection`. Hosts the same
/// `RegionPickerView` used by onboarding step 4.
struct RegionSettingsView: View {
    @Environment(\.appState) private var appState

    var body: some View {
        RegionPickerView(
            selection: Binding(
                get: { appState.regionSelection },
                set: { appState.regionSelection = $0 }
            ),
            onCommit: { /* no auto-pop; user dismisses via back chevron */ }
        )
        .navigationTitle(L10n.Settings.Region.title)
    }
}
