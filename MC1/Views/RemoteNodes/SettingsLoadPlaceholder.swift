import SwiftUI

/// Inline placeholder shown in place of a node-settings value before its section
/// fetch resolves: "Loading…" while in flight, "Failed to load" on error, an em
/// dash otherwise.
struct SettingsLoadPlaceholder: View {
    let isLoading: Bool
    let hasError: Bool

    var body: some View {
        Text(
            isLoading
                ? L10n.RemoteNodes.RemoteNodes.Settings.loading
                : (hasError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : NodeStatusViewModel.emDash)
        )
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
