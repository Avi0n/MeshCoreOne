import MC1Services
import SwiftUI

struct BlockingSection: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.appState) private var appState
  @State private var recentlyBlockedCount: Int?

  var body: some View {
    Section {
      TextRulesLoadNotice()

      NavigationLink(value: SettingsSubpage.textRules) {
        TintedLabel(L10n.Settings.TextRules.title, systemImage: "line.3.horizontal.decrease")
      }

      NavigationLink(value: SettingsSubpage.recentlyBlocked) {
        HStack {
          TintedLabel(L10n.Settings.TextRules.Archive.title, systemImage: "tray")
          Spacer()
          if let recentlyBlockedCount {
            Text(recentlyBlockedCount, format: .number)
              .foregroundStyle(.secondary)
          }
        }
      }
      .task {
        let store = appState.connectionManager.persistenceStore
        let changes = store.recentlyBlockedUpdates()
        await refreshRecentlyBlockedCount(from: store)
        for await _ in changes {
          await refreshRecentlyBlockedCount(from: store)
        }
      }

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

  private func refreshRecentlyBlockedCount(from store: PersistenceStore) async {
    if let count = try? await store.recentlyBlockedCount() {
      recentlyBlockedCount = count
    }
  }
}
