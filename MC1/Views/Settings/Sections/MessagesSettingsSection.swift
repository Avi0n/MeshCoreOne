import MC1Services
import SwiftUI

/// Settings section for message display preferences
struct MessagesSettingsSection: View {
    @AppStorage(AppStorageKey.showIncomingPath.rawValue) private var showIncomingPath = false
    @AppStorage(AppStorageKey.showIncomingHopCount.rawValue) private var showIncomingHopCount = false
    @AppStorage(AppStorageKey.showIncomingRegion.rawValue) private var showIncomingRegion = false

    var body: some View {
        Section {
            Toggle(L10n.Settings.Messages.showIncomingPath, isOn: $showIncomingPath)
            Toggle(L10n.Settings.Messages.showIncomingHopCount, isOn: $showIncomingHopCount)
            Toggle(L10n.Settings.Messages.showIncomingRegion, isOn: $showIncomingRegion)
        } header: {
            Text(L10n.Settings.Messages.header)
        } footer: {
            Text(L10n.Settings.Messages.footer)
        }
    }
}

#Preview {
    Form {
        MessagesSettingsSection()
    }
}
