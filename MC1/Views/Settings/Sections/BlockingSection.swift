import MC1Services
import SwiftUI

/// Settings section for managing blocked channel users and contacts.
struct BlockingSection: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        Section {
            NavigationLink {
                BlockedChannelSendersView()
            } label: {
                TintedLabel(L10n.Settings.Blocking.channelSenders, systemImage: "person.crop.circle.badge.xmark")
            }

            NavigationLink {
                BlockedContactsView()
            } label: {
                TintedLabel(L10n.Settings.Blocking.contacts, systemImage: "hand.raised.slash")
            }
        } header: {
            Text(L10n.Settings.Blocking.header)
        }
        .themedRowBackground(theme)
    }
}
