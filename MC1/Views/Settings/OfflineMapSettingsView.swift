import SwiftUI

struct OfflineMapSettingsView: View {
    var body: some View {
        ContentUnavailableView {
            Label(L10n.Settings.OfflineMaps.emptyTitle, systemImage: "map")
        } description: {
            Text(L10n.Settings.OfflineMaps.emptyDescription)
        }
        .navigationTitle(L10n.Settings.OfflineMaps.title)
    }
}
