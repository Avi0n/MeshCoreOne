import MC1Services
import SwiftUI

struct RecentlyBlockedView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var entries: [RecentlyBlockedMessageDTO] = []
  @State private var errorMessage: String?

  var body: some View {
    List {
      Section {
        if entries.isEmpty {
          Text(L10n.Settings.TextRules.Archive.empty)
            .foregroundStyle(.secondary)
        }
        ForEach(entries) { entry in
          archiveRow(entry)
        }
      } footer: {
        Text(L10n.Settings.TextRules.Archive.footer(PersistenceStore.recentlyBlockedRetentionLimit))
      }
      .themedRowBackground(theme)
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

  private func archiveRow(_ entry: RecentlyBlockedMessageDTO) -> some View {
    VStack(alignment: .leading) {
      labeled(
        L10n.Settings.TextRules.Field.message,
        Text(highlighted(entry.body, matches: entry.match.bodyHighlights))
      )
      VStack(alignment: .leading) {
        labeled(
          L10n.Settings.TextRules.Archive.rule,
          Text(verbatim: L10n.Settings.TextRules.ruleSummary(
            entry.match.rule.phrase,
            entry.match.rule.summary
          ))
        )
        if let sender = entry.sender {
          labeled(
            L10n.Settings.TextRules.Field.sender,
            Text(highlighted(sender, matches: entry.match.senderHighlights))
          )
        }
      }
      .font(.subheadline)
      VStack(alignment: .leading) {
        labeled(
          L10n.Settings.TextRules.Archive.chat,
          Text(verbatim: entry.conversationName ?? L10n.Settings.TextRules.Archive.unknownConversation)
        )
        labeled(
          L10n.Settings.TextRules.Archive.myNode,
          Text(verbatim: entry.radioName ?? L10n.Settings.TextRules.Archive.unknownRadio)
        )
        labeled(
          L10n.Settings.TextRules.Archive.time,
          Text(entry.arrivedAt, format: .dateTime.month().day().hour().minute())
        )
      }
      .font(.footnote)
      .foregroundStyle(.secondary)
    }
    .fixedSize(horizontal: false, vertical: true)
    .accessibilityElement(children: .combine)
  }

  private func highlighted(_ text: String, matches: [TextRulesPreference.RuleMatch.Highlight]) -> AttributedString {
    var attributed = AttributedString(text)
    for match in matches {
      guard let range = match.range(in: text),
            let start = AttributedString.Index(range.lowerBound, within: attributed),
            let end = AttributedString.Index(range.upperBound, within: attributed) else { continue }
      attributed[start..<end].backgroundColor = .yellow
      attributed[start..<end].foregroundColor = .black
      attributed[start..<end].underlineStyle = .single
    }
    return attributed
  }

  private func labeled(_ label: String, _ value: Text) -> Text {
    Text(verbatim: L10n.Settings.TextRules.Archive.labelPrefix(label)) + value
  }

  private func refresh(from store: PersistenceStore) async {
    do {
      entries = try await store.recentlyBlockedMessages()
    } catch {
      errorMessage = L10n.Localizable.Common.Error.failedToLoad
    }
  }
}
