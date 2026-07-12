import MC1Services
import SwiftUI

/// Settings section for managing blocked channel users and contacts.
struct BlockingSection: View {
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      NavigationLink(value: SettingsSubpage.blockedChannelSenders) {
        TintedLabel(L10n.Settings.Blocking.channelSenders, systemImage: "person.crop.circle.badge.xmark")
      }

      NavigationLink(value: SettingsSubpage.blockedContacts) {
        TintedLabel(L10n.Settings.Blocking.contacts, systemImage: "hand.raised.slash")
      }
    } header: {
      Text(L10n.Settings.Blocking.header)
    }
    .themedRowBackground(theme)
  }
}
