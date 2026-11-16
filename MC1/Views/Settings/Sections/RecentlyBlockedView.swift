import MC1Services
import SwiftUI

struct RecentlyBlockedView: View {
  struct RadioGroup: Identifiable {
    let radioID: UUID
    let radioName: String?
    let entries: [RecentlyBlockedMessageDTO]

    var id: UUID {
      radioID
    }
  }

  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var entries: [RecentlyBlockedMessageDTO] = []
  @State private var errorMessage: String?

  var body: some View {
    let groups = Self.radioGroups(in: entries)
    List {
      if groups.count > 1 {
        ForEach(groups) { group in
          radioSection(group, isLast: group.id == groups.last?.id)
        }
      } else {
        messageSection(entries)
      }
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.TextRules.Archive.title)
    .navigationBarTitleDisplayMode(.inline)
    .errorAlert($errorMessage)
    .task {
      let store = appState.connectionManager.persistenceStore
      let changes = store.recentlyBlockedUpdates()
      await refresh(from: store)
      for await _ in changes {
        await refresh(from: store)
      }
    }
  }

  static func radioGroups(in entries: [RecentlyBlockedMessageDTO]) -> [RadioGroup] {
    var order: [UUID] = []
    var grouped: [UUID: [RecentlyBlockedMessageDTO]] = [:]
    var names: [UUID: String] = [:]
    for entry in entries {
      let radioID = entry.identity.radioID
      if grouped[radioID] == nil {
        order.append(radioID)
      }
      grouped[radioID, default: []].append(entry)
      if names[radioID] == nil, let radioName = entry.radioName {
        names[radioID] = radioName
      }
    }
    return order.map { radioID in
      RadioGroup(radioID: radioID, radioName: names[radioID], entries: grouped[radioID] ?? [])
    }
  }

  private func messageSection(_ rows: [RecentlyBlockedMessageDTO]) -> some View {
    Section {
      if rows.isEmpty {
        Text(L10n.Settings.TextRules.Archive.empty)
          .foregroundStyle(.secondary)
      }
      ForEach(rows) { entry in
        RecentlyBlockedRow(entry: entry)
      }
    } footer: {
      retentionFooter
    }
    .themedRowBackground(theme)
  }

  @ViewBuilder
  private func radioSection(_ group: RadioGroup, isLast: Bool) -> some View {
    if isLast {
      Section {
        messageRows(group.entries)
      } header: {
        Text(radioHeader(group))
      } footer: {
        retentionFooter
      }
      .themedRowBackground(theme)
    } else {
      Section {
        messageRows(group.entries)
      } header: {
        Text(radioHeader(group))
      }
      .themedRowBackground(theme)
    }
  }

  private func messageRows(_ rows: [RecentlyBlockedMessageDTO]) -> some View {
    ForEach(rows) { entry in
      RecentlyBlockedRow(entry: entry)
    }
  }

  private func radioHeader(_ group: RadioGroup) -> String {
    group.radioName ?? L10n.Settings.TextRules.Archive.unknownRadio
  }

  private var retentionFooter: Text {
    Text(L10n.Settings.TextRules.Archive.footer(PersistenceStore.recentlyBlockedRetentionLimit))
  }

  private func refresh(from store: PersistenceStore) async {
    do {
      entries = try await store.recentlyBlockedMessages()
    } catch {
      errorMessage = L10n.Localizable.Common.Error.failedToLoad
    }
  }
}
