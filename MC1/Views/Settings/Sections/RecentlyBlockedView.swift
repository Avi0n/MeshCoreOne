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
      Text(highlighted(entry.body, matches: entry.match.bodyHighlights))
        .fixedSize(horizontal: false, vertical: true)
      Text(reason(for: entry.match))
        .font(.subheadline)
      if let sender = entry.sender {
        Text(highlighted(sender, matches: entry.match.senderHighlights))
          .font(.subheadline)
      }
      Text(entry.conversationName ?? L10n.Settings.TextRules.Archive.unknownConversation)
        .foregroundStyle(.secondary)
      Text(entry.radioName ?? L10n.Settings.TextRules.Archive.unknownRadio)
        .foregroundStyle(.secondary)
      Text(entry.arrivedAt, format: .dateTime.month().day().hour().minute())
        .foregroundStyle(.secondary)
    }
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

  private func reason(for match: TextRulesPreference.RuleMatch) -> String {
    let fields: String = if match.bodyHighlights.isEmpty {
      L10n.Settings.TextRules.Field.sender
    } else if match.senderHighlights.isEmpty {
      L10n.Settings.TextRules.Field.message
    } else {
      L10n.Settings.TextRules.Archive.fieldsBoth
    }
    return L10n.Settings.TextRules.Archive.reason(match.rule.phrase, fields, match.rule.match.title(for: match.rule.lookIn))
  }

  private func refresh(from store: PersistenceStore) async {
    do {
      entries = try await store.recentlyBlockedMessages()
    } catch {
      errorMessage = L10n.Localizable.Common.Error.failedToLoad
    }
  }
}
